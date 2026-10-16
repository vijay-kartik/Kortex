package dev.kortex.app.ui.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.kortex.app.MainActivity
import dev.kortex.app.R
import dev.kortex.app.domain.share.ShareResultNotifier
import dev.kortex.design.R as DesignR

/**
 * Posts the "agent results" notification that reopens a share run's chat session in [MainActivity].
 * While [redact] (App lock is on) it leaves out the session title and the answer, which can be about
 * a bank statement or a medical report.
 */
class ShareResultNotifications(
    private val appContext: Context,
    private val redact: () -> Boolean,
) : ShareResultNotifier {

    override fun notifyDone(sessionId: String, title: String, answer: String) {
        val manager = NotificationManagerCompat.from(appContext)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                appContext.getString(R.string.share_results_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = appContext.getString(R.string.share_results_channel_description)
            }
        )

        val openIntent = Intent(appContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(MainActivity.EXTRA_OPEN_SESSION_ID, sessionId)
        }
        val contentIntent = PendingIntent.getActivity(
            appContext,
            sessionId.hashCode(),
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(DesignR.drawable.ic_kortex_mark)
            .setContentTitle(appContext.getString(R.string.share_results_title))
            .setSubText(appContext.getString(R.string.share_results_tap_to_view))
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
        val details = details(title, answer, redact())
        if (details == null) {
            builder.setContentText(appContext.getString(R.string.share_results_redacted_text))
        } else {
            builder.setContentText(details.title).setStyle(NotificationCompat.BigTextStyle().bigText(details.answer))
        }
        val notification = builder.build()

        // Pre-13 devices need no permission; on 13+ the composer asked for it. If the user
        // declined, the result still lands in History — only the ping is lost.
        runCatching { manager.notify(sessionId.hashCode(), notification) }
    }

    /** What the notification may show of the run: its title and the start of the answer. */
    internal data class Details(val title: String, val answer: String)

    internal companion object {
        private const val CHANNEL_ID = "share_agent_results"

        /** Null while [redact]: the notification then says only that the result is ready. */
        fun details(title: String, answer: String, redact: Boolean): Details? =
            if (redact) null else Details(title, answer.take(300))
    }
}
