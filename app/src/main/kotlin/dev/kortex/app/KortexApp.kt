package dev.kortex.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager

/** Holds the app-wide [KortexContainer]. Registered as android:name in the manifest. */
class KortexApp : Application() {
    lateinit var container: KortexContainer
        private set

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        container = KortexContainer(this)
    }

    private fun createNotificationChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                WaForegroundService.CHANNEL_ID,
                "WhatsApp Connection",
                NotificationManager.IMPORTANCE_LOW
            )
        )
    }
}
