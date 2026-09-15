package dev.kortex.app

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/** Holds the app-wide [KortexContainer]. Registered as android:name in the manifest. */
@HiltAndroidApp
class KortexApp : Application() {
    lateinit var container: KortexContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = KortexContainer(this)
    }
}
