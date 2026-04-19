package org.tyflocentrum.android

import android.app.Application
import org.tyflocentrum.android.core.AppContainer

class TyflocentrumApplication : Application() {
    lateinit var appContainer: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        appContainer = AppContainer(this)
    }
}
