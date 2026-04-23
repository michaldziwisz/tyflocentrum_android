package org.tyflocentrum.android.core.playback

import android.content.Intent
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import org.tyflocentrum.android.TyflocentrumApplication

@UnstableApi
class PlaybackService : MediaSessionService() {
    companion object {
        @Volatile
        private var running = false

        @Volatile
        private var startRequested = false

        fun markStartRequestedIfNeeded(): Boolean = synchronized(this) {
            if (running || startRequested) {
                false
            } else {
                startRequested = true
                true
            }
        }

        fun clearStartRequest() {
            synchronized(this) {
                startRequested = false
            }
        }
    }

    private val playerController: PlayerController
        get() = (application as TyflocentrumApplication).appContainer.playerController

    override fun onCreate() {
        super.onCreate()
        synchronized(Companion) {
            running = true
            startRequested = false
        }
        addSession(playerController.mediaSession)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession {
        return playerController.mediaSession
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!isPlaybackOngoing) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        synchronized(Companion) {
            running = false
            startRequested = false
        }
        super.onDestroy()
    }
}
