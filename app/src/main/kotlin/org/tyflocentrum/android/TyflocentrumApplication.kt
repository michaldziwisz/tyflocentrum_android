package org.tyflocentrum.android

import androidx.media3.cast.Cast
import androidx.media3.common.util.UnstableApi
import android.app.Application
import org.tyflocentrum.android.core.AppContainer

@UnstableApi
class TyflocentrumApplication : Application() {
    lateinit var appContainer: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        Cast.getSingletonInstance(this).initialize()
        appContainer = AppContainer(this)
    }
}
