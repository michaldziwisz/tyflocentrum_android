package org.tyflocentrum.android.core.playback

import android.content.Context
import android.content.Intent
import android.view.KeyEvent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.DeviceInfo
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.cast.CastPlayer
import androidx.media3.cast.RemoteCastPlayer
import androidx.media3.cast.SessionAvailabilityListener
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.common.util.UnstableApi
import androidx.core.content.ContextCompat
import com.google.android.gms.cast.MediaError
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.tyflocentrum.android.core.model.AppSettings
import org.tyflocentrum.android.core.model.PlaybackRatePolicy
import org.tyflocentrum.android.core.model.PlaybackRateRememberMode
import org.tyflocentrum.android.core.model.PlayerRequest
import org.tyflocentrum.android.core.model.PlayerUiState
import org.tyflocentrum.android.core.storage.AppPreferencesRepository

@UnstableApi
class PlayerController(
    context: Context,
    private val preferences: AppPreferencesRepository,
    private val diagnostics: CastDiagnosticsLogger
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val localPlayer = ExoPlayer.Builder(appContext)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                .build(),
            true
        )
        .setSeekBackIncrementMs(SEEK_INCREMENT_MS)
        .setSeekForwardIncrementMs(SEEK_INCREMENT_MS)
        .setHandleAudioBecomingNoisy(true)
        .build()
    private val remotePlayer = RemoteCastPlayer.Builder(appContext)
        .setMediaItemConverter(LiveAwareMediaItemConverter())
        .setSeekBackIncrementMs(SEEK_INCREMENT_MS)
        .setSeekForwardIncrementMs(SEEK_INCREMENT_MS)
        .build()
    private val player = CastPlayer.Builder(appContext)
        .setLocalPlayer(localPlayer)
        .setRemotePlayer(remotePlayer)
        .build()
    val mediaSession: MediaSession = MediaSession.Builder(appContext, player)
        .setCallback(
            object : MediaSession.Callback {
                override fun onMediaButtonEvent(
                    session: MediaSession,
                    controllerInfo: MediaSession.ControllerInfo,
                    intent: Intent
                ): Boolean {
                    if (mediaButtonOverride?.invoke(intent) == true) {
                        return true
                    }
                    if (handleLiveMediaButtonEvent(intent)) {
                        return true
                    }
                    return super.onMediaButtonEvent(session, controllerInfo, intent)
                }
            }
        )
        .setMediaButtonPreferences(buildMediaButtons(isLive = false))
        .build()

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    private var settingsSnapshot = AppSettings()
    private var progressJob: Job? = null
    private var lastResumeSaveAt: Long = 0
    private var lastAudibleVolume = 1f
    private var isMutedByUser = false
    private var stopPlaybackAfterCastDisconnect = false
    private var lastPlaybackType = player.deviceInfo.playbackType
    @Volatile
    private var mediaButtonOverride: ((Intent) -> Boolean)? = null
    private val castContext = runCatching { CastContext.getSharedInstance(appContext) }.getOrNull()
    private var observedRemoteMediaClient: RemoteMediaClient? = null
    private val remoteMediaClientCallback = object : RemoteMediaClient.Callback() {
        override fun onStatusUpdated() {
            diagnostics.log("cast.remote.status", describeRemoteMediaClient(observedRemoteMediaClient))
        }

        override fun onMetadataUpdated() {
            diagnostics.log("cast.remote.metadata", describeRemoteMediaClient(observedRemoteMediaClient))
        }

        override fun onQueueStatusUpdated() {
            diagnostics.log("cast.remote.queue", describeRemoteMediaClient(observedRemoteMediaClient))
        }

        override fun onPreloadStatusUpdated() {
            diagnostics.log("cast.remote.preload", describeRemoteMediaClient(observedRemoteMediaClient))
        }

        override fun onSendingRemoteMediaRequest() {
            diagnostics.log("cast.remote.request", describeRemoteMediaClient(observedRemoteMediaClient))
        }

        override fun onMediaError(error: MediaError) {
            diagnostics.log("cast.remote.error", error.toString())
        }
    }
    private val castAvailabilityListener = object : SessionAvailabilityListener {
        override fun onCastSessionAvailable() {
            diagnostics.log("cast.session.available", describePlayerSnapshot())
            handleCastSessionReady("sessionAvailable", castContext?.sessionManager?.currentCastSession)
        }

        override fun onCastSessionUnavailable() {
            diagnostics.log("cast.session.unavailable", describePlayerSnapshot())
            attachRemoteMediaClient(null)
        }
    }
    private val castSessionListener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarting(session: CastSession) {
            diagnostics.log("cast.session.starting", describeSession(session))
        }

        override fun onSessionStarted(session: CastSession, sessionId: String) {
            diagnostics.log("cast.session.started", "${describeSession(session)} sessionId=$sessionId")
            handleCastSessionReady("sessionStarted", session)
        }

        override fun onSessionStartFailed(session: CastSession, error: Int) {
            diagnostics.log("cast.session.startFailed", "${describeSession(session)} error=$error")
            attachRemoteMediaClient(null)
        }

        override fun onSessionEnding(session: CastSession) {
            diagnostics.log("cast.session.ending", describeSession(session))
            prepareForCastDisconnect("sessionEnding", session)
        }

        override fun onSessionEnded(session: CastSession, error: Int) {
            diagnostics.log("cast.session.ended", "${describeSession(session)} error=$error")
            attachRemoteMediaClient(null)
            finalizeCastDisconnectIfNeeded("sessionEnded")
        }

        override fun onSessionResuming(session: CastSession, sessionId: String) {
            diagnostics.log("cast.session.resuming", "${describeSession(session)} sessionId=$sessionId")
        }

        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) {
            diagnostics.log("cast.session.resumed", "${describeSession(session)} suspended=$wasSuspended")
            handleCastSessionReady("sessionResumed", session)
        }

        override fun onSessionResumeFailed(session: CastSession, error: Int) {
            diagnostics.log("cast.session.resumeFailed", "${describeSession(session)} error=$error")
            attachRemoteMediaClient(null)
            stopPlaybackAfterCastDisconnect = false
        }

        override fun onSessionSuspended(session: CastSession, reason: Int) {
            diagnostics.log("cast.session.suspended", "${describeSession(session)} reason=$reason")
            stopPlaybackAfterCastDisconnect = false
        }
    }

    init {
        remotePlayer.setSessionAvailabilityListener(castAvailabilityListener)
        diagnostics.log("player.init", describePlayerSnapshot())
        player.addListener(
            object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    diagnostics.log("player.playbackState", describePlayerSnapshot())
                    updateUiState()
                    if (playbackState == Player.STATE_ENDED) {
                        _uiState.value.current?.takeIf { !it.isLive }?.let { request ->
                            scope.launch { preferences.clearResumePosition(request.url) }
                        }
                    }
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    diagnostics.log("player.isPlaying", describePlayerSnapshot())
                    if (isPlaying) {
                        ensurePlaybackServiceRunningIfNeeded()
                    }
                    updateUiState()
                }

                override fun onDeviceInfoChanged(deviceInfo: DeviceInfo) {
                    val previousPlaybackType = lastPlaybackType
                    lastPlaybackType = deviceInfo.playbackType
                    diagnostics.log("player.deviceInfo", describePlayerSnapshot())
                    updateUiState()
                    handlePlaybackRouteTransition(previousPlaybackType, deviceInfo.playbackType)
                }

                override fun onPlayerError(error: PlaybackException) {
                    diagnostics.log(
                        "player.error",
                        "${describePlayerSnapshot()} message=${error.localizedMessage ?: error.message ?: "unknown"}"
                    )
                    _uiState.value = _uiState.value.copy(
                        errorMessage = error.localizedMessage ?: "Nie udało się odtworzyć materiału."
                    )
                }
            }
        )

        castContext?.sessionManager?.addSessionManagerListener(castSessionListener, CastSession::class.java)
        attachRemoteMediaClient(castContext?.sessionManager?.currentCastSession)
        diagnostics.log(
            "cast.context",
            if (castContext == null) {
                "unavailable"
            } else {
                "ready state=${castContext.castState}"
            }
        )

        preferences.settingsFlow
            .onEach { settings ->
                settingsSnapshot = settings
                applyCurrentPlaybackRateFromPreferences()
            }
            .launchIn(scope)

        progressJob = scope.launch {
            while (true) {
                delay(500)
                updateUiState()
                maybePersistResumePosition()
            }
        }
    }

    fun play(request: PlayerRequest) {
        playInternal(request)
    }

    private fun playInternal(
        request: PlayerRequest,
        forceReloadLive: Boolean = false,
        origin: String = "ui"
    ) {
        scope.launch {
            runCatching {
                val sameUrl = _uiState.value.current?.url == request.url
                val sameLoadedItem = sameUrl && hasLoadedMediaItemFor(request)
                val shouldForceReloadLive = forceReloadLive ||
                    (request.isLive && sameLoadedItem && !isPlaybackPendingOrActive())
                val sameItem = sameLoadedItem && !shouldForceReloadLive
                diagnostics.log(
                    "play.request",
                    "url=${request.url} live=${request.isLive} sameUrl=$sameUrl sameLoadedItem=$sameLoadedItem " +
                        "forceReloadLive=$shouldForceReloadLive origin=$origin ${describePlayerSnapshot()}"
                )
                val startPosition = when {
                    request.isLive -> C.TIME_UNSET
                    request.initialSeekMs != null -> request.initialSeekMs
                    sameItem -> C.TIME_UNSET
                    else -> preferences.loadResumePosition(request.url)
                } ?: C.TIME_UNSET

                val preparedMediaItem = if (!sameItem) buildMediaItem(request) else null
                if (!sameItem) {
                    if (player.currentMediaItem != null) {
                        if (player.isCommandAvailable(Player.COMMAND_STOP)) {
                            player.stop()
                        }
                        if (player.isCommandAvailable(Player.COMMAND_CHANGE_MEDIA_ITEMS)) {
                            player.clearMediaItems()
                        }
                    }
                    player.playWhenReady = true
                    player.setMediaItem(preparedMediaItem ?: buildMediaItem(request), startPosition)
                    mediaSession.setMediaButtonPreferences(buildMediaButtons(isLive = request.isLive))
                    player.prepare()
                    _uiState.value = _uiState.value.copy(current = request, errorMessage = null)
                } else if (startPosition != C.TIME_UNSET && startPosition >= 0) {
                    player.seekTo(startPosition)
                }

                player.setPlaybackSpeed(resolvePlaybackRate(request))
                player.playWhenReady = true
                ensurePlaybackServiceRunningIfNeeded()
                updateUiState()
                diagnostics.log("play.applied", describePlayerSnapshot())
            }.onFailure { error ->
                diagnostics.log(
                    "play.failure",
                    "url=${request.url} ${describePlayerSnapshot()} " +
                        "message=${error.localizedMessage ?: error.message ?: "unknown"}"
                )
                _uiState.value = _uiState.value.copy(
                    current = request,
                    isPlaying = false,
                    playWhenReady = false,
                    isBuffering = false,
                    errorMessage = error.localizedMessage ?: "Nie udało się odtworzyć materiału."
                )
            }
        }
    }

    fun togglePlayPause(request: PlayerRequest) {
        diagnostics.log(
            "togglePlayPause",
            "target=${request.url} hasLoaded=${hasLoadedMediaItemFor(request)} ${describePlayerSnapshot()}"
        )
        if (_uiState.value.current?.url == request.url) {
            if (isPlaybackPendingOrActive()) {
                pause()
            } else if (player.currentMediaItem != null) {
                resume()
            } else {
                playInternal(request)
            }
        } else {
            playInternal(request)
        }
    }

    fun resume() {
        val current = _uiState.value.current
        diagnostics.log("resume.request", describePlayerSnapshot())
        if (current?.isLive == true) {
            if (player.isCastSessionAvailable && player.currentMediaItem != null) {
                player.play()
                ensurePlaybackServiceRunningIfNeeded()
                updateUiState()
                return
            }
            playInternal(current)
            return
        }
        player.playWhenReady = true
        ensurePlaybackServiceRunningIfNeeded()
        updateUiState()
    }

    fun pause() {
        diagnostics.log("pause.request", describePlayerSnapshot())
        if (_uiState.value.current?.isLive == true) {
            if (player.isCastSessionAvailable && player.currentMediaItem != null) {
                player.pause()
                updateUiState()
                return
            }
            stopLivePlayback()
            return
        }
        player.pause()
        updateUiState()
    }

    fun toggleMute() {
        if (!isMutedByUser) {
            val currentVolume = player.volume
            if (currentVolume > MIN_AUDIBLE_VOLUME) {
                lastAudibleVolume = currentVolume
            }
            player.volume = 0f
            isMutedByUser = true
        } else {
            restoreAudibleVolume("toggleMute")
        }
        diagnostics.log(
            "volume.toggleMute",
            "volume=${player.volume} muted=$isMutedByUser remote=${player.isCastSessionAvailable} ${describePlayerSnapshot()}"
        )
        updateUiState()
    }

    fun skipForward(seconds: Int = 30) {
        if (!player.isCommandAvailable(Player.COMMAND_SEEK_FORWARD)) return
        repeat((seconds * 1000L / SEEK_INCREMENT_MS).toInt().coerceAtLeast(1)) {
            player.seekForward()
        }
        updateUiState()
    }

    fun skipBackward(seconds: Int = 30) {
        if (!player.isCommandAvailable(Player.COMMAND_SEEK_BACK)) return
        repeat((seconds * 1000L / SEEK_INCREMENT_MS).toInt().coerceAtLeast(1)) {
            player.seekBack()
        }
        updateUiState()
    }

    fun seekTo(positionMs: Long) {
        val duration = player.duration.takeIf { it > 0 }
        val clamped = duration?.let { positionMs.coerceIn(0, it) } ?: positionMs.coerceAtLeast(0)
        player.seekTo(clamped)
        updateUiState()
    }

    fun cyclePlaybackRate() {
        setPlaybackRate(PlaybackRatePolicy.next(_uiState.value.playbackRate))
    }

    fun setPlaybackRate(rate: Float) {
        scope.launch {
            val current = _uiState.value.current
            if (current?.isLive == true) {
                player.setPlaybackSpeed(1f)
                updateUiState()
                return@launch
            }
            val normalized = PlaybackRatePolicy.normalized(rate)
            player.setPlaybackSpeed(normalized)
            when (settingsSnapshot.playbackRateRememberMode) {
                PlaybackRateRememberMode.GLOBAL -> preferences.savePlaybackRateGlobal(normalized)
                PlaybackRateRememberMode.PER_EPISODE -> current?.takeIf { !it.isLive }?.let {
                    preferences.savePlaybackRateForUrl(it.url, normalized)
                }
            }
            updateUiState()
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(errorMessage = null)
    }

    fun isCastAvailable(): Boolean = player.isCastSessionAvailable

    fun setMediaButtonOverride(handler: ((Intent) -> Boolean)?) {
        mediaButtonOverride = handler
    }

    private fun applyCurrentPlaybackRateFromPreferences() {
        val current = _uiState.value.current ?: return
        scope.launch {
            player.setPlaybackSpeed(resolvePlaybackRate(current))
            updateUiState()
        }
    }

    private fun maybePersistResumePosition() {
        val current = _uiState.value.current ?: return
        if (current.isLive || player.duration <= 0) return
        val now = System.currentTimeMillis()
        if (now - lastResumeSaveAt < 5_000) return
        lastResumeSaveAt = now
        scope.launch {
            preferences.saveResumePosition(current.url, player.currentPosition)
        }
    }

    private fun updateUiState() {
        val currentRequest = syncCurrentRequest("uiState")
        val currentVolume = player.volume
        if (currentVolume > MIN_AUDIBLE_VOLUME) {
            lastAudibleVolume = currentVolume
            if (isMutedByUser) {
                isMutedByUser = false
            }
        }
        val duration = player.duration.takeIf { it > 0 }
        _uiState.value = _uiState.value.copy(
            current = currentRequest ?: _uiState.value.current,
            isPlaying = player.isPlaying,
            playWhenReady = player.playWhenReady,
            isMuted = isMutedByUser,
            isBuffering = player.playbackState == Player.STATE_BUFFERING,
            isRemotePlayback = player.deviceInfo.playbackType == DeviceInfo.PLAYBACK_TYPE_REMOTE,
            durationMs = duration,
            elapsedMs = player.currentPosition.coerceAtLeast(0),
            playbackRate = player.playbackParameters.speed
        )
    }

    fun release() {
        progressJob?.cancel()
        castContext?.sessionManager?.removeSessionManagerListener(castSessionListener, CastSession::class.java)
        attachRemoteMediaClient(null)
        mediaSession.release()
        player.release()
    }

    private fun ensurePlaybackServiceRunningIfNeeded() {
        if (player.isCastSessionAvailable ||
            player.deviceInfo.playbackType == DeviceInfo.PLAYBACK_TYPE_REMOTE ||
            player.currentMediaItem == null ||
            !(player.playWhenReady || player.isPlaying || player.playbackState == Player.STATE_BUFFERING)
        ) {
            return
        }
        if (!PlaybackService.markStartRequestedIfNeeded()) {
            return
        }
        runCatching {
            ContextCompat.startForegroundService(
                appContext,
                Intent(appContext, PlaybackService::class.java)
            )
        }.onFailure { error ->
            PlaybackService.clearStartRequest()
            throw error
        }
    }

    private fun buildMediaButtons(isLive: Boolean): List<CommandButton> {
        if (isLive) return emptyList()
        return listOf(
            CommandButton.Builder(CommandButton.ICON_SKIP_BACK_30)
                .setPlayerCommand(Player.COMMAND_SEEK_BACK)
                .setDisplayName("Cofnij 30 sekund")
                .build(),
            CommandButton.Builder(CommandButton.ICON_SKIP_FORWARD_30)
                .setPlayerCommand(Player.COMMAND_SEEK_FORWARD)
                .setDisplayName("Przewiń 30 sekund")
                .build()
            )
    }

    private fun stopLivePlayback() {
        diagnostics.log("live.stop", describePlayerSnapshot())
        player.playWhenReady = false
        if (player.currentMediaItem != null) {
            if (player.isCommandAvailable(Player.COMMAND_STOP)) {
                player.stop()
            }
            if (player.isCommandAvailable(Player.COMMAND_CHANGE_MEDIA_ITEMS)) {
                player.clearMediaItems()
            }
        }
        updateUiState()
    }

    private fun handleLiveMediaButtonEvent(intent: Intent): Boolean {
        val current = _uiState.value.current?.takeIf { it.isLive } ?: return false
        val keyEvent = intent.mediaButtonKeyEvent() ?: return false
        if (!keyEvent.isLivePlaybackControlKey()) return false
        diagnostics.log(
            "live.mediaButton",
            "keyCode=${keyEvent.keyCode} action=${keyEvent.action} repeat=${keyEvent.repeatCount} ${describePlayerSnapshot()}"
        )
        return when (keyEvent.action) {
            KeyEvent.ACTION_DOWN -> {
                if (keyEvent.repeatCount == 0) {
                    when (keyEvent.keyCode) {
                        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                        KeyEvent.KEYCODE_HEADSETHOOK -> {
                            if (isPlaybackPendingOrActive()) stopLivePlayback() else playInternal(current)
                        }
                        KeyEvent.KEYCODE_MEDIA_PLAY -> playInternal(current)
                        KeyEvent.KEYCODE_MEDIA_PAUSE,
                        KeyEvent.KEYCODE_MEDIA_STOP -> stopLivePlayback()
                    }
                }
                true
            }
            KeyEvent.ACTION_UP -> true
            else -> false
        }
    }

    private companion object {
        const val SEEK_INCREMENT_MS = 30_000L
        const val RADIO_SOURCE_STREAM_URL = "https://radio.tyflopodcast.net/hls/stream.m3u8"
        const val RADIO_CAST_STREAM_URL = "https://radio.tyflopodcast.net/"
        const val RADIO_CAST_STREAM_MIME_TYPE = MimeTypes.AUDIO_MPEG
        const val MIN_AUDIBLE_VOLUME = 0.01f
        const val MIN_RESTORE_VOLUME = 0.25f
    }

    private suspend fun resolvePlaybackRate(request: PlayerRequest): Float {
        if (request.isLive) return 1f
        val preferredRate = when (settingsSnapshot.playbackRateRememberMode) {
            PlaybackRateRememberMode.GLOBAL -> preferences.loadPlaybackRateGlobal()
            PlaybackRateRememberMode.PER_EPISODE -> preferences.loadPlaybackRateForUrl(request.url)
        } ?: 1f
        return PlaybackRatePolicy.normalized(preferredRate)
    }

    private suspend fun buildMediaItem(request: PlayerRequest): MediaItem {
        val metadataBuilder = MediaMetadata.Builder()
            .setTitle(request.title)
            .setArtist(request.subtitle)
        return MediaItem.Builder()
            .setMediaId(request.url)
            .setUri(request.url)
            .apply {
                if (request.isLive) {
                    val (castStreamUrl, castStreamMimeType) = resolveLiveCastStreamTarget(request.url)
                    setMimeType(MimeTypes.APPLICATION_M3U8)
                    setLiveConfiguration(MediaItem.LiveConfiguration.Builder().build())
                    metadataBuilder
                        .setMediaType(MediaMetadata.MEDIA_TYPE_RADIO_STATION)
                        .setAlbumTitle(request.title)
                        .setArtist(request.subtitle ?: "Transmisja na żywo")
                        .setAlbumArtist(request.title)
                        .setStation(request.title)
                    metadataBuilder.setExtras(
                        LiveAwareMediaItemConverter.castExtras(
                            url = castStreamUrl,
                            mimeType = castStreamMimeType
                        )
                    )
                } else if (request.url.endsWith(".m3u8", ignoreCase = true)) {
                    setMimeType(MimeTypes.APPLICATION_M3U8)
                }
            }
            .setMediaMetadata(metadataBuilder.build())
            .build()
    }

    private fun hasLoadedMediaItemFor(request: PlayerRequest): Boolean {
        val currentItem = player.currentMediaItem ?: return false
        val currentUrl = currentItem.localConfiguration?.uri?.toString()
            ?: currentItem.requestMetadata.mediaUri?.toString()
            ?: currentItem.mediaId.takeIf { it.isNotBlank() }
        return currentUrl == request.url
    }

    private fun isPlaybackPendingOrActive(): Boolean {
        return player.isPlaying || (player.playWhenReady && player.playbackState != Player.STATE_IDLE)
    }

    private fun handleCastSessionReady(trigger: String, session: CastSession?) {
        attachRemoteMediaClient(session)
        val request = syncCurrentRequest("cast:$trigger") ?: return
        if (request.isLive) {
            ensureAudiblePlaybackOnCast(trigger)
        }
        maybeAutoStartOnCast(trigger, request)
    }

    private fun syncCurrentRequest(reason: String): PlayerRequest? {
        _uiState.value.current?.let { return it }
        val recovered = recoverCurrentRequestFromMediaItem(player.currentMediaItem)
            ?: recoverCurrentRequestFromRemoteMediaClient(observedRemoteMediaClient)
            ?: return null
        diagnostics.log(
            "play.request.recover",
            "reason=$reason url=${recovered.url} live=${recovered.isLive} title=${recovered.title}"
        )
        _uiState.value = _uiState.value.copy(current = recovered, errorMessage = null)
        return recovered
    }

    private fun recoverCurrentRequestFromMediaItem(mediaItem: MediaItem?): PlayerRequest? {
        mediaItem ?: return null
        val metadata = mediaItem.mediaMetadata
        val candidates = listOfNotNull(
            mediaItem.localConfiguration?.uri?.toString(),
            mediaItem.requestMetadata.mediaUri?.toString(),
            mediaItem.mediaId.takeIf { it.isNotBlank() },
            metadata.extras?.getString(LiveAwareMediaItemConverter.EXTRA_CAST_STREAM_URL)
        )
        val isLive = mediaItem.liveConfiguration != MediaItem.LiveConfiguration.UNSET
        if (isTyfloRadioRequest(candidates, isLive, metadata)) {
            return buildRecoveredRadioRequest(
                titleHint = metadata.title?.toString(),
                subtitleHint = metadata.artist?.toString()
            )
        }
        val sourceUrl = candidates.firstOrNull { it.isNotBlank() } ?: return null
        return PlayerRequest(
            url = sourceUrl,
            title = metadata.title?.toString()?.takeIf { it.isNotBlank() } ?: sourceUrl,
            subtitle = metadata.artist?.toString()?.takeIf { it.isNotBlank() },
            isLive = isLive
        )
    }

    private fun recoverCurrentRequestFromRemoteMediaClient(
        remoteClient: RemoteMediaClient?
    ): PlayerRequest? {
        val mediaInfo = remoteClient?.mediaInfo ?: return null
        val candidates = listOfNotNull(mediaInfo.contentUrl, mediaInfo.contentId)
        val isLive = mediaInfo.streamType == MediaInfo.STREAM_TYPE_LIVE
        if (isTyfloRadioRequest(candidates, isLive, null)) {
            return buildRecoveredRadioRequest(
                titleHint = mediaInfo.metadata
                    ?.getString(com.google.android.gms.cast.MediaMetadata.KEY_TITLE),
                subtitleHint = mediaInfo.metadata
                    ?.getString(com.google.android.gms.cast.MediaMetadata.KEY_SUBTITLE)
            )
        }
        val sourceUrl = candidates.firstOrNull { it.isNotBlank() } ?: return null
        return PlayerRequest(
            url = sourceUrl,
            title = mediaInfo.metadata
                ?.getString(com.google.android.gms.cast.MediaMetadata.KEY_TITLE)
                ?.takeIf { it.isNotBlank() }
                ?: sourceUrl,
            subtitle = mediaInfo.metadata
                ?.getString(com.google.android.gms.cast.MediaMetadata.KEY_SUBTITLE)
                ?.takeIf { it.isNotBlank() },
            isLive = isLive
        )
    }

    private fun isTyfloRadioRequest(
        candidates: List<String>,
        isLive: Boolean,
        metadata: MediaMetadata?
    ): Boolean {
        if (candidates.any(::matchesTyfloRadioUrl)) {
            return true
        }
        if (!isLive) {
            return false
        }
        if (metadata?.mediaType == MediaMetadata.MEDIA_TYPE_RADIO_STATION) {
            return true
        }
        val labels = listOfNotNull(
            metadata?.title?.toString(),
            metadata?.artist?.toString(),
            metadata?.albumTitle?.toString(),
            metadata?.station?.toString()
        )
        return labels.any { it.contains("tyfloradio", ignoreCase = true) }
    }

    private fun buildRecoveredRadioRequest(
        titleHint: String?,
        subtitleHint: String?
    ): PlayerRequest {
        return PlayerRequest(
            url = RADIO_SOURCE_STREAM_URL,
            title = titleHint?.takeIf { it.isNotBlank() } ?: "Tyfloradio",
            subtitle = subtitleHint?.takeIf { it.isNotBlank() },
            isLive = true
        )
    }

    private fun maybeAutoStartOnCast(trigger: String, request: PlayerRequest) {
        if (!player.isCastSessionAvailable) return
        attachRemoteMediaClient(castContext?.sessionManager?.currentCastSession)
        runCatching { observedRemoteMediaClient?.requestStatus() }
        val remoteReady = matchesActiveRemotePlayback(request, observedRemoteMediaClient)
        diagnostics.log(
            "cast.autoplay",
            "trigger=$trigger target=${request.url} live=${request.isLive} " +
                "sameItem=${hasLoadedMediaItemFor(request)} remoteReady=$remoteReady ${describePlayerSnapshot()}"
        )
        if (!remoteReady) {
            resetStaleRemotePlayback(trigger, request, observedRemoteMediaClient)
            playInternal(
                request,
                forceReloadLive = request.isLive,
                origin = "castAutoplay:$trigger"
            )
            return
        }
        player.playWhenReady = true
        player.play()
        updateUiState()
    }

    private fun matchesActiveRemotePlayback(
        request: PlayerRequest,
        remoteClient: RemoteMediaClient?
    ): Boolean {
        if (remoteClient == null || !remoteClient.hasMediaSession()) {
            return false
        }
        val expectedUrls = if (request.isLive) {
            listOf(RADIO_CAST_STREAM_URL)
        } else {
            listOf(request.url)
        }
        val actualContentId = remoteClient.mediaInfo?.contentId
        val actualContentUrl = remoteClient.mediaInfo?.contentUrl
        val matchesRequestedStream = listOfNotNull(actualContentId, actualContentUrl)
            .any { candidate ->
                expectedUrls.any { expectedUrl ->
                    normalizeStreamUrl(candidate) == normalizeStreamUrl(expectedUrl)
                }
            }
        if (!matchesRequestedStream) {
            return false
        }
        return remoteClient.isPlaying ||
            remoteClient.isPaused ||
            remoteClient.isBuffering ||
            remoteClient.playerState == 5
    }

    private fun resetStaleRemotePlayback(
        trigger: String,
        request: PlayerRequest,
        remoteClient: RemoteMediaClient?
    ) {
        if (remoteClient == null || !remoteClient.hasMediaSession()) return
        val actualContent = remoteClient.mediaInfo?.contentUrl
            ?: remoteClient.mediaInfo?.contentId
            ?: return
        diagnostics.log(
            "cast.remote.reset",
            "trigger=$trigger target=${request.url} actual=$actualContent " +
                describeRemoteMediaClient(remoteClient)
        )
        runCatching { remoteClient.stop() }
    }

    private fun prepareForCastDisconnect(trigger: String, session: CastSession?) {
        val shouldStopPlayback = player.deviceInfo.playbackType == DeviceInfo.PLAYBACK_TYPE_REMOTE ||
            _uiState.value.isRemotePlayback ||
            player.currentMediaItem != null
        stopPlaybackAfterCastDisconnect = shouldStopPlayback
        diagnostics.log(
            "cast.disconnect.prepare",
            "trigger=$trigger shouldStop=$shouldStopPlayback ${describeSession(session)} ${describePlayerSnapshot()}"
        )
        runCatching { session?.remoteMediaClient?.stop() }
    }

    private fun finalizeCastDisconnectIfNeeded(trigger: String) {
        if (!stopPlaybackAfterCastDisconnect) return
        stopPlaybackAfterCastDisconnect = false
        diagnostics.log(
            "cast.disconnect.finalize",
            "trigger=$trigger ${describePlayerSnapshot()}"
        )
        player.playWhenReady = false
        if (player.currentMediaItem != null) {
            if (player.isCommandAvailable(Player.COMMAND_STOP)) {
                player.stop()
            }
            if (player.isCommandAvailable(Player.COMMAND_CHANGE_MEDIA_ITEMS)) {
                player.clearMediaItems()
            }
        }
        updateUiState()
    }

    private fun handlePlaybackRouteTransition(previousPlaybackType: Int, currentPlaybackType: Int) {
        val movedFromRemoteToLocal =
            previousPlaybackType == DeviceInfo.PLAYBACK_TYPE_REMOTE &&
                currentPlaybackType != DeviceInfo.PLAYBACK_TYPE_REMOTE
        if (!movedFromRemoteToLocal) return
        if (stopPlaybackAfterCastDisconnect) {
            finalizeCastDisconnectIfNeeded("deviceInfoChanged")
            return
        }
        if (castContext?.sessionManager?.currentCastSession != null) {
            diagnostics.log(
                "cast.disconnect.defer",
                "reason=sessionStillPresent ${describePlayerSnapshot()}"
            )
            return
        }
        stopPlaybackAfterCastDisconnect = true
        finalizeCastDisconnectIfNeeded("deviceInfoChanged")
    }

    private fun resolveLiveCastStreamTarget(sourceUrl: String): Pair<String, String> {
        diagnostics.log(
            "cast.stream.resolve",
            "source=$sourceUrl castUrl=$RADIO_CAST_STREAM_URL mime=$RADIO_CAST_STREAM_MIME_TYPE"
        )
        return RADIO_CAST_STREAM_URL to RADIO_CAST_STREAM_MIME_TYPE
    }

    private fun ensureAudiblePlaybackOnCast(trigger: String) {
        val shouldRestore = isMutedByUser || player.volume <= MIN_AUDIBLE_VOLUME
        if (!shouldRestore) {
            diagnostics.log(
                "cast.volume.keep",
                "trigger=$trigger volume=${player.volume} ${describePlayerSnapshot()}"
            )
            return
        }
        restoreAudibleVolume("cast:$trigger")
    }

    private fun restoreAudibleVolume(reason: String) {
        val restoredVolume = lastAudibleVolume.coerceIn(MIN_RESTORE_VOLUME, 1f)
        player.volume = restoredVolume
        isMutedByUser = false
        diagnostics.log(
            "volume.restore",
            "reason=$reason volume=${player.volume} remote=${player.isCastSessionAvailable} ${describePlayerSnapshot()}"
        )
    }

    private fun attachRemoteMediaClient(session: CastSession?) {
        val newClient = session?.remoteMediaClient
        if (observedRemoteMediaClient === newClient) return
        observedRemoteMediaClient?.unregisterCallback(remoteMediaClientCallback)
        observedRemoteMediaClient = newClient
        newClient?.registerCallback(remoteMediaClientCallback)
        diagnostics.log(
            "cast.remote.attach",
            "${describeSession(session)} ${describeRemoteMediaClient(newClient)}"
        )
    }

    private fun describePlayerSnapshot(): String {
        val currentItem = player.currentMediaItem
        val currentItemUrl = currentItem?.localConfiguration?.uri?.toString()
            ?: currentItem?.requestMetadata?.mediaUri?.toString()
            ?: currentItem?.mediaId
            ?: "-"
        val currentRequestUrl = _uiState.value.current?.url ?: "-"
        return buildString {
            append("remote=")
            append(player.deviceInfo.playbackType == DeviceInfo.PLAYBACK_TYPE_REMOTE)
            append(" castSession=")
            append(player.isCastSessionAvailable)
            append(" isPlaying=")
            append(player.isPlaying)
            append(" playWhenReady=")
            append(player.playWhenReady)
            append(" playbackState=")
            append(playbackStateName(player.playbackState))
            append(" currentRequest=")
            append(currentRequestUrl)
            append(" currentItem=")
            append(currentItemUrl)
        }
    }

    private fun describeSession(session: CastSession?): String {
        if (session == null) return "session=none"
        val deviceName = session.castDevice?.friendlyName ?: "unknown"
        return "session=device($deviceName)"
    }

    private fun describeRemoteMediaClient(client: RemoteMediaClient?): String {
        if (client == null) return "remoteClient=none"
        return runCatching {
            buildString {
                append("remoteClient{")
                append("hasMediaSession=")
                append(client.hasMediaSession())
                append(", isLive=")
                append(client.isLiveStream)
                append(", isPlaying=")
                append(client.isPlaying)
                append(", isPaused=")
                append(client.isPaused)
                append(", isBuffering=")
                append(client.isBuffering)
                append(", playerState=")
                append(castPlayerStateName(client.playerState))
                append(", idleReason=")
                append(client.idleReason)
                append(", positionMs=")
                append(client.approximateStreamPosition)
                append(", media=")
                append(client.mediaInfo?.contentId ?: "-")
                append("}")
            }
        }.getOrElse { error ->
            "remoteClient=error(${error.javaClass.simpleName}:${error.message})"
        }
    }

    private fun playbackStateName(state: Int): String {
        return when (state) {
            Player.STATE_IDLE -> "IDLE"
            Player.STATE_BUFFERING -> "BUFFERING"
            Player.STATE_READY -> "READY"
            Player.STATE_ENDED -> "ENDED"
            else -> state.toString()
        }
    }

    private fun castPlayerStateName(state: Int): String {
        return when (state) {
            RemoteMediaClient.STATUS_SUCCEEDED -> "STATUS_SUCCEEDED"
            RemoteMediaClient.STATUS_FAILED -> "STATUS_FAILED"
            RemoteMediaClient.STATUS_REPLACED -> "STATUS_REPLACED"
            1 -> "IDLE"
            2 -> "PLAYING"
            3 -> "PAUSED"
            4 -> "BUFFERING"
            5 -> "LOADING"
            else -> state.toString()
        }
    }

    private fun normalizeStreamUrl(url: String): String {
        return url.trim().removeSuffix("/")
    }

    private fun matchesTyfloRadioUrl(url: String): Boolean {
        val normalized = normalizeStreamUrl(url)
        return normalized == normalizeStreamUrl(RADIO_SOURCE_STREAM_URL) ||
            normalized == normalizeStreamUrl(RADIO_CAST_STREAM_URL)
    }

    private fun Intent.mediaButtonKeyEvent(): KeyEvent? {
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra(Intent.EXTRA_KEY_EVENT)
        }
    }

    private fun KeyEvent.isLivePlaybackControlKey(): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_MEDIA_STOP,
            KeyEvent.KEYCODE_HEADSETHOOK -> true
            else -> false
        }
    }
}
