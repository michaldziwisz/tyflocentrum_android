package org.tyflocentrum.android.core.playback

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
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

class PlayerController(
    context: Context,
    private val preferences: AppPreferencesRepository
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val player = ExoPlayer.Builder(appContext)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                .build(),
            true
        )
        .setHandleAudioBecomingNoisy(true)
        .build()
    private val mediaSession = MediaSession.Builder(appContext, player).build()

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
                val sameItem = _uiState.value.current?.url == request.url
                val startPosition = when {
                    request.isLive -> C.TIME_UNSET
                    request.initialSeekMs != null -> request.initialSeekMs
                    else -> preferences.loadResumePosition(request.url)
                } ?: C.TIME_UNSET

                if (!sameItem) {
                    val mediaItem = MediaItem.Builder()
                        .setUri(request.url)
                        .setMediaMetadata(
                            MediaMetadata.Builder()
                                .setTitle(request.title)
                                .setArtist(request.subtitle)
                                .build()
                        )
                        .build()
                    player.setMediaItem(mediaItem, startPosition)
                    player.prepare()
                    _uiState.value = _uiState.value.copy(current = request, errorMessage = null)
                } else if (startPosition != C.TIME_UNSET && startPosition >= 0) {
                    player.seekTo(startPosition)
                }

                val preferredRate = when (settingsSnapshot.playbackRateRememberMode) {
                    PlaybackRateRememberMode.GLOBAL -> preferences.loadPlaybackRateGlobal()
                    PlaybackRateRememberMode.PER_EPISODE -> preferences.loadPlaybackRateForUrl(request.url)
                } ?: 1f
                player.setPlaybackSpeed(PlaybackRatePolicy.normalized(preferredRate))
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
        player.playWhenReady = true
        updateUiState()
    }

    fun pause() {
        player.pause()
        updateUiState()
    }

    fun skipForward(seconds: Int = 30) {
        val duration = player.duration.takeIf { it > 0 } ?: return
        player.seekTo((player.currentPosition + seconds * 1000L).coerceAtMost(duration))
        updateUiState()
    }

    fun skipBackward(seconds: Int = 30) {
        player.seekTo((player.currentPosition - seconds * 1000L).coerceAtLeast(0))
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
            val normalized = PlaybackRatePolicy.normalized(rate)
            player.setPlaybackSpeed(normalized)
            val current = _uiState.value.current
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

    private fun applyCurrentPlaybackRateFromPreferences() {
        val current = _uiState.value.current ?: return
        if (current.isLive) return
        scope.launch {
            val preferredRate = when (settingsSnapshot.playbackRateRememberMode) {
                PlaybackRateRememberMode.GLOBAL -> preferences.loadPlaybackRateGlobal()
                PlaybackRateRememberMode.PER_EPISODE -> preferences.loadPlaybackRateForUrl(current.url)
            } ?: 1f
            player.setPlaybackSpeed(PlaybackRatePolicy.normalized(preferredRate))
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
}
