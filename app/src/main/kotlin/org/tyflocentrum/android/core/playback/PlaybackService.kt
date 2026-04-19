package org.tyflocentrum.android.core.playback

import android.content.Intent
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import org.tyflocentrum.android.TyflocentrumApplication

@UnstableApi
class PlaybackService : MediaSessionService() {
    private val playerController: PlayerController
        get() = (application as TyflocentrumApplication).appContainer.playerController

    override fun onCreate() {
        super.onCreate()
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
}
