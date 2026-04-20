package org.tyflocentrum.android.core.playback

import android.content.Intent
import android.content.Context
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
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.common.util.UnstableApi
import androidx.core.content.ContextCompat
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
    private val preferences: AppPreferencesRepository
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
        .setMediaButtonPreferences(buildMediaButtons(isLive = false))
        .build()

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    private var settingsSnapshot = AppSettings()
    private var progressJob: Job? = null
    private var lastResumeSaveAt: Long = 0

    init {
        player.addListener(
            object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    updateUiState()
                    if (playbackState == Player.STATE_ENDED) {
                        _uiState.value.current?.takeIf { !it.isLive }?.let { request ->
                            scope.launch { preferences.clearResumePosition(request.url) }
                        }
                    }
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    updateUiState()
                }

                override fun onDeviceInfoChanged(deviceInfo: DeviceInfo) {
                    updateUiState()
                }

                override fun onPlayerError(error: PlaybackException) {
                    _uiState.value = _uiState.value.copy(
                        errorMessage = error.localizedMessage ?: "Nie udało się odtworzyć materiału."
                    )
                }
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
        scope.launch {
            runCatching {
                ensurePlaybackServiceRunning()
                val sameItem = _uiState.value.current?.url == request.url && hasLoadedMediaItemFor(request)
                val startPosition = when {
                    request.isLive -> C.TIME_UNSET
                    request.initialSeekMs != null -> request.initialSeekMs
                    else -> preferences.loadResumePosition(request.url)
                } ?: C.TIME_UNSET

                if (!sameItem) {
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
            }.onFailure { error ->
                _uiState.value = _uiState.value.copy(
                    current = request,
                    isPlaying = false,
                    isBuffering = false,
                    errorMessage = error.localizedMessage ?: "Nie udało się odtworzyć materiału."
                )
            }
        }
    }

    fun togglePlayPause(request: PlayerRequest) {
        if (_uiState.value.current?.url == request.url && player.currentMediaItem != null) {
            if (player.isPlaying) {
                pause()
            } else {
                resume()
            }
        } else {
            play(request)
        }
    }

    fun resume() {
        ensurePlaybackServiceRunning()
        player.playWhenReady = true
        updateUiState()
    }

    fun pause() {
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
            isBuffering = player.playbackState == Player.STATE_BUFFERING,
            isRemotePlayback = player.deviceInfo.playbackType == DeviceInfo.PLAYBACK_TYPE_REMOTE,
            durationMs = duration,
            elapsedMs = player.currentPosition.coerceAtLeast(0),
            playbackRate = player.playbackParameters.speed
        )
    }

    fun release() {
        progressJob?.cancel()
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
}
