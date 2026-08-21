package dev.kortex.app

import android.app.Application

/** Holds the app-wide [KortexContainer]. Registered as android:name in the manifest. */
class KortexApp : Application() {
    lateinit var container: KortexContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // The WhatsApp notification channel is created by the :wa module's own foreground
        // service, so hosts do not have to know about it.
        container = KortexContainer(this)
        // Constructing the manager no longer does any I/O; start() is what reads persisted
        // credentials and reconnects an already-linked device.
        container.whatsApp.start()
    }
}
