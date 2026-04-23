package org.tyflocentrum.android.core.playback

import android.content.Context
import android.content.Intent
import android.os.SystemClock
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
    private var castRecoveryJob: Job? = null
    private var lastResumeSaveAt: Long = 0
    private var nextCastStartupTraceId: Int = 1
    private var castStartupTrace: CastStartupTrace? = null
    @Volatile
    private var mediaButtonOverride: ((Intent) -> Boolean)? = null
    private val castContext = runCatching { CastContext.getSharedInstance(appContext) }.getOrNull()
    private var observedRemoteMediaClient: RemoteMediaClient? = null
    private val remoteMediaClientCallback = object : RemoteMediaClient.Callback() {
        override fun onStatusUpdated() {
            diagnostics.log("cast.remote.status", describeRemoteMediaClient(observedRemoteMediaClient))
            updateCastStartupTraceFromRemoteClient("status")
        }

        override fun onMetadataUpdated() {
            diagnostics.log("cast.remote.metadata", describeRemoteMediaClient(observedRemoteMediaClient))
            updateCastStartupTraceFromRemoteClient("metadata")
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
            ensureCastStartupTrace("sessionAvailable")
            diagnostics.log("cast.session.available", describePlayerSnapshot())
            markCastStartupTrace("sessionAvailable")
            attachRemoteMediaClient(castContext?.sessionManager?.currentCastSession)
            scheduleLiveCastRecovery("sessionAvailable")
        }

        override fun onCastSessionUnavailable() {
            diagnostics.log("cast.session.unavailable", describePlayerSnapshot())
            castRecoveryJob?.cancel()
            finishCastStartupTrace("sessionUnavailable")
            attachRemoteMediaClient(null)
        }
    }
    private val castSessionListener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarting(session: CastSession) {
            ensureCastStartupTrace("sessionStarting")
            diagnostics.log("cast.session.starting", describeSession(session))
            markCastStartupTrace("sessionStarting", "device=${session.castDevice?.friendlyName ?: "unknown"}")
        }

        override fun onSessionStarted(session: CastSession, sessionId: String) {
            ensureCastStartupTrace("sessionStarted")
            diagnostics.log("cast.session.started", "${describeSession(session)} sessionId=$sessionId")
            markCastStartupTrace("sessionStarted", "sessionId=$sessionId")
            attachRemoteMediaClient(session)
            scheduleLiveCastRecovery("sessionStarted")
        }

        override fun onSessionStartFailed(session: CastSession, error: Int) {
            diagnostics.log("cast.session.startFailed", "${describeSession(session)} error=$error")
            castRecoveryJob?.cancel()
            finishCastStartupTrace("sessionStartFailed", "error=$error")
            attachRemoteMediaClient(null)
        }

        override fun onSessionEnding(session: CastSession) {
            diagnostics.log("cast.session.ending", describeSession(session))
        }

        override fun onSessionEnded(session: CastSession, error: Int) {
            diagnostics.log("cast.session.ended", "${describeSession(session)} error=$error")
            castRecoveryJob?.cancel()
            finishCastStartupTrace("sessionEnded", "error=$error")
            attachRemoteMediaClient(null)
        }

        override fun onSessionResuming(session: CastSession, sessionId: String) {
            diagnostics.log("cast.session.resuming", "${describeSession(session)} sessionId=$sessionId")
        }

        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) {
            ensureCastStartupTrace("sessionResumed")
            diagnostics.log("cast.session.resumed", "${describeSession(session)} suspended=$wasSuspended")
            markCastStartupTrace("sessionResumed", "suspended=$wasSuspended")
            attachRemoteMediaClient(session)
            scheduleLiveCastRecovery("sessionResumed")
        }

        override fun onSessionResumeFailed(session: CastSession, error: Int) {
            diagnostics.log("cast.session.resumeFailed", "${describeSession(session)} error=$error")
            castRecoveryJob?.cancel()
            finishCastStartupTrace("sessionResumeFailed", "error=$error")
            attachRemoteMediaClient(null)
        }

        override fun onSessionSuspended(session: CastSession, reason: Int) {
            diagnostics.log("cast.session.suspended", "${describeSession(session)} reason=$reason")
            castRecoveryJob?.cancel()
            finishCastStartupTrace("sessionSuspended", "reason=$reason")
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
                    updateUiState()
                }

                override fun onDeviceInfoChanged(deviceInfo: DeviceInfo) {
                    diagnostics.log("player.deviceInfo", describePlayerSnapshot())
                    updateUiState()
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
                ensurePlaybackServiceRunning()
                val sameUrl = _uiState.value.current?.url == request.url
                val sameLoadedItem = sameUrl && hasLoadedMediaItemFor(request)
                val shouldForceReloadLive = forceReloadLive ||
                    (request.isLive && sameLoadedItem && !isPlaybackPendingOrActive())
                val sameItem = sameLoadedItem && !shouldForceReloadLive
                if (!request.isLive) {
                    castRecoveryJob?.cancel()
                }
                diagnostics.log(
                    "play.request",
                    "url=${request.url} live=${request.isLive} sameUrl=$sameUrl sameLoadedItem=$sameLoadedItem " +
                        "forceReloadLive=$shouldForceReloadLive origin=$origin ${describePlayerSnapshot()}"
                )
                if (request.isLive && (player.isCastSessionAvailable || castContext?.sessionManager?.currentCastSession != null)) {
                    startCastStartupTrace(request, origin)
                    markCastStartupTrace("playRequested", "origin=$origin")
                } else if (!request.isLive) {
                    finishCastStartupTrace("nonLivePlayRequested", "url=${request.url}")
                }
                val startPosition = when {
                    request.isLive -> C.TIME_UNSET
                    request.initialSeekMs != null -> request.initialSeekMs
                    sameItem -> C.TIME_UNSET
                    else -> preferences.loadResumePosition(request.url)
                } ?: C.TIME_UNSET

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
                    val mediaItem = buildMediaItem(request)
                    player.setMediaItem(mediaItem, startPosition)
                    mediaSession.setMediaButtonPreferences(buildMediaButtons(isLive = request.isLive))
                    player.prepare()
                    _uiState.value = _uiState.value.copy(current = request, errorMessage = null)
                } else if (startPosition != C.TIME_UNSET && startPosition >= 0) {
                    player.seekTo(startPosition)
                }

                player.setPlaybackSpeed(resolvePlaybackRate(request))
                player.playWhenReady = true
                updateUiState()
                diagnostics.log("play.applied", describePlayerSnapshot())
            }.onFailure { error ->
                if (request.isLive) {
                    finishCastStartupTrace(
                        "playFailure",
                        "message=${error.localizedMessage ?: error.message ?: "unknown"}"
                    )
                }
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
            if (player.isCastSessionAvailable || castContext?.sessionManager?.currentCastSession != null) {
                startCastStartupTrace(current, "resume")
                markCastStartupTrace("resumeRequested")
            }
            playInternal(current)
            return
        }
        ensurePlaybackServiceRunning()
        player.playWhenReady = true
        updateUiState()
    }

    fun pause() {
        diagnostics.log("pause.request", describePlayerSnapshot())
        if (_uiState.value.current?.isLive == true) {
            stopLivePlayback()
            return
        }
        player.pause()
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
        val duration = player.duration.takeIf { it > 0 }
        _uiState.value = _uiState.value.copy(
            isPlaying = player.isPlaying,
            playWhenReady = player.playWhenReady,
            isBuffering = player.playbackState == Player.STATE_BUFFERING,
            isRemotePlayback = player.deviceInfo.playbackType == DeviceInfo.PLAYBACK_TYPE_REMOTE,
            durationMs = duration,
            elapsedMs = player.currentPosition.coerceAtLeast(0),
            playbackRate = player.playbackParameters.speed
        )
    }

    fun release() {
        progressJob?.cancel()
        castRecoveryJob?.cancel()
        castContext?.sessionManager?.removeSessionManagerListener(castSessionListener, CastSession::class.java)
        attachRemoteMediaClient(null)
        mediaSession.release()
        player.release()
    }

    private fun ensurePlaybackServiceRunning() {
        ContextCompat.startForegroundService(
            appContext,
            Intent(appContext, PlaybackService::class.java)
        )
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
        castRecoveryJob?.cancel()
        finishCastStartupTrace("liveStopped")
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
        const val RADIO_CAST_STREAM_URL = "https://radio.tyflopodcast.net/"
    }

    private suspend fun resolvePlaybackRate(request: PlayerRequest): Float {
        if (request.isLive) return 1f
        val preferredRate = when (settingsSnapshot.playbackRateRememberMode) {
            PlaybackRateRememberMode.GLOBAL -> preferences.loadPlaybackRateGlobal()
            PlaybackRateRememberMode.PER_EPISODE -> preferences.loadPlaybackRateForUrl(request.url)
        } ?: 1f
        return PlaybackRatePolicy.normalized(preferredRate)
    }

    private fun buildMediaItem(request: PlayerRequest): MediaItem {
        val metadataBuilder = MediaMetadata.Builder()
            .setTitle(request.title)
            .setArtist(request.subtitle)
        return MediaItem.Builder()
            .setMediaId(request.url)
            .setUri(request.url)
            .apply {
                if (request.isLive) {
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
                            url = RADIO_CAST_STREAM_URL,
                            mimeType = MimeTypes.AUDIO_MPEG
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

    private fun ensureCastStartupTrace(reason: String) {
        val request = _uiState.value.current?.takeIf { it.isLive } ?: return
        if (!_uiState.value.playWhenReady) return
        val trace = castStartupTrace
        if (trace != null && trace.requestUrl == request.url) {
            return
        }
        startCastStartupTrace(request, reason)
    }

    private fun startCastStartupTrace(request: PlayerRequest, origin: String) {
        val receiverName = castContext?.sessionManager?.currentCastSession?.castDevice?.friendlyName
        val previousTrace = castStartupTrace
        if (previousTrace?.requestUrl == request.url &&
            previousTrace.receiverName == receiverName &&
            previousTrace.origin == origin
        ) {
            return
        }
        previousTrace?.let {
            diagnostics.log(
                "cast.startup.superseded",
                "trace=${it.id} elapsedMs=${SystemClock.elapsedRealtime() - it.startedAtMs} " +
                    "newOrigin=$origin receiver=${receiverName ?: "-"}"
            )
        }
        val trace = CastStartupTrace(
            id = nextCastStartupTraceId++,
            requestUrl = request.url,
            title = request.title,
            receiverName = receiverName,
            origin = origin,
            startedAtMs = SystemClock.elapsedRealtime()
        )
        castStartupTrace = trace
        diagnostics.log(
            "cast.startup.begin",
            "trace=${trace.id} origin=$origin receiver=${trace.receiverName ?: "-"} " +
                "title=${trace.title} url=${trace.requestUrl}"
        )
    }

    private fun markCastStartupTrace(stage: String, details: String? = null) {
        val trace = castStartupTrace ?: return
        if (!trace.loggedStages.add(stage)) return
        diagnostics.log(
            "cast.startup.$stage",
            "trace=${trace.id} elapsedMs=${SystemClock.elapsedRealtime() - trace.startedAtMs} " +
                "receiver=${trace.receiverName ?: "-"}${details?.let { " $it" } ?: ""}"
        )
    }

    private fun finishCastStartupTrace(stage: String, details: String? = null) {
        val trace = castStartupTrace ?: return
        if (trace.loggedStages.add(stage)) {
            diagnostics.log(
                "cast.startup.$stage",
                "trace=${trace.id} elapsedMs=${SystemClock.elapsedRealtime() - trace.startedAtMs} " +
                    "receiver=${trace.receiverName ?: "-"}${details?.let { " $it" } ?: ""}"
            )
        }
        castStartupTrace = null
    }

    private fun updateCastStartupTraceFromRemoteClient(source: String) {
        val request = _uiState.value.current?.takeIf { it.isLive } ?: return
        val remoteClient = observedRemoteMediaClient ?: return
        val trace = castStartupTrace ?: return
        if (trace.requestUrl != request.url) {
            finishCastStartupTrace("requestChanged", "source=$source")
            return
        }
        if (remoteClient.hasMediaSession()) {
            markCastStartupTrace("remoteHasSession", "source=$source")
        }
        val actualContentId = remoteClient.mediaInfo?.contentId
        if (actualContentId == RADIO_CAST_STREAM_URL) {
            markCastStartupTrace("remoteMatchedMedia", "source=$source")
        }
        if (remoteClient.playerState == 5 || remoteClient.isBuffering) {
            markCastStartupTrace("remoteLoading", "source=$source")
        }
        if (remoteClient.isPlaying) {
            finishCastStartupTrace("remotePlaying", "source=$source")
        }
    }

    private fun scheduleLiveCastRecovery(trigger: String) {
        val request = _uiState.value.current?.takeIf { it.isLive } ?: return
        if (!_uiState.value.playWhenReady) return
        castRecoveryJob?.cancel()
        castRecoveryJob = scope.launch {
            val attemptDelaysMs = listOf(0L, 750L, 2_000L, 5_000L, 15_000L, 30_000L)
            for ((index, waitMs) in attemptDelaysMs.withIndex()) {
                if (waitMs > 0) delay(waitMs)
                val currentRequest = _uiState.value.current
                if (currentRequest?.url != request.url || currentRequest.isLive != true || !_uiState.value.playWhenReady) {
                    diagnostics.log(
                        "cast.recover.stop",
                        "trigger=$trigger attempt=${index + 1} reason=requestChanged ${describePlayerSnapshot()}"
                    )
                    return@launch
                }
                if (!player.isCastSessionAvailable) {
                    diagnostics.log(
                        "cast.recover.stop",
                        "trigger=$trigger attempt=${index + 1} reason=sessionUnavailable ${describePlayerSnapshot()}"
                    )
                    return@launch
                }

                attachRemoteMediaClient(castContext?.sessionManager?.currentCastSession)
                runCatching { observedRemoteMediaClient?.requestStatus() }
                val remoteReady = matchesActiveRemotePlayback(currentRequest, observedRemoteMediaClient)
                diagnostics.log(
                    "cast.recover.check",
                    "trigger=$trigger attempt=${index + 1} ready=$remoteReady ${describePlayerSnapshot()} " +
                        describeRemoteMediaClient(observedRemoteMediaClient)
                )
                markCastStartupTrace(
                    "recoverCheck$index",
                    "trigger=$trigger attempt=${index + 1} ready=$remoteReady"
                )
                if (remoteReady) {
                    return@launch
                }
                if (index > 0) {
                    diagnostics.log(
                        "cast.recover.reload",
                        "trigger=$trigger attempt=${index + 1} ${describePlayerSnapshot()}"
                    )
                    markCastStartupTrace("recoverReload$index", "trigger=$trigger attempt=${index + 1}")
                    playInternal(
                        currentRequest,
                        forceReloadLive = true,
                        origin = "castRecovery:$trigger:${index + 1}"
                    )
                }
            }
            diagnostics.log(
                "cast.recover.timeout",
                "trigger=$trigger ${describePlayerSnapshot()} ${describeRemoteMediaClient(observedRemoteMediaClient)}"
            )
            markCastStartupTrace("recoverTimeout", "trigger=$trigger")
        }
    }

    private fun matchesActiveRemotePlayback(
        request: PlayerRequest,
        remoteClient: RemoteMediaClient?
    ): Boolean {
        if (!request.isLive || remoteClient == null || !remoteClient.hasMediaSession()) {
            return false
        }
        val expectedContentId = RADIO_CAST_STREAM_URL
        val actualContentId = remoteClient.mediaInfo?.contentId
        if (actualContentId != expectedContentId) {
            return false
        }
        return remoteClient.isPlaying ||
            remoteClient.isBuffering ||
            remoteClient.playerState == 5
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

    private data class CastStartupTrace(
        val id: Int,
        val requestUrl: String,
        val title: String,
        val receiverName: String?,
        val origin: String,
        val startedAtMs: Long,
        val loggedStages: MutableSet<String> = mutableSetOf()
    )
}
