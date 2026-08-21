package dev.kortex.wa.session

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Keeps the WhatsApp socket alive in the background. Started by [WhatsAppManager.connect] and
 * looks up the process's active manager via [WhatsAppManager.current] rather than a host app's DI
 * container, since Android instantiates services from the manifest, not from caller code.
 */
class WaForegroundService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * The SDK owns its own notification channel. Relying on the host app to have registered
     * [CHANNEL_ID] meant a consumer that had not happened to create it got a foreground service
     * posting to a channel that does not exist — silently invisible, and a crash risk on the
     * stricter foreground-service rules. Creating a channel that already exists is a no-op, so
     * this is safe even when the host registers one too.
     */
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification("Connecting…"))
        val manager = WhatsAppManager.current() ?: return START_NOT_STICKY.also { stopSelf() }
        manager.attachService(this)
        scope.launch { manager.runClient() }
        return START_STICKY
    }

    fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(WhatsAppManager.current()?.notificationTitle ?: CHANNEL_NAME)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()

    override fun onDestroy() {
        scope.cancel()
        WhatsAppManager.current()?.detachService()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val NOTIF_ID = 1001
        const val CHANNEL_ID = "wa_conn"
        const val CHANNEL_NAME = "WhatsApp Connection"
    }
}
