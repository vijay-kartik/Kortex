package dev.kortex.app

import android.app.Application

/** Holds the app-wide [KortexContainer]. Registered as android:name in the manifest. */
class KortexApp : Application() {
    lateinit var container: KortexContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = KortexContainer(this)
    }
}
