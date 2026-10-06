package dev.kortex.finance.sms

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.kortex.finance.R
import dev.kortex.finance.domain.usecase.AutoSaved
import dev.kortex.finance.domain.usecase.SmsEntryType
import dev.kortex.finance.reminders.FinanceReminders
import dev.kortex.finance.ui.FinanceRoute
import dev.kortex.finance.ui.common.FinanceFormat
import dev.kortex.finance.ui.recurring.RecurringLabels

/**
 * What a run of [BankSmsWorker] tells the user (docs/SMS_AUTO_PLAN.md, phase 5): each entry saved
 * on its own, with Undo, and one "N bank SMS to review" for the rest. Nothing for OTPs and adverts.
 * An import of earlier SMS ([BankSmsImportWorker]) shows its progress, then one summary.
 */
object BankSmsNotifications {
    /** Undo on an auto-saved entry; the app's action receiver hands it to [BankSms.undo]. */
    const val ACTION_UNDO = "dev.kortex.finance.action.UNDO_SMS_ENTRY"
    const val EXTRA_SMS_ID = "dev.kortex.finance.SMS_ID"

    private const val CHANNEL_ID = "bank-sms"
    private const val REVIEW_ID = 0x5B6
    private const val IMPORT_ID = 0x5B7

    /** "Saved ₹420.00 at Swiggy" · "HDFC ••4471 · Food". */
    fun title(saved: AutoSaved): String {
        val amount = FinanceFormat.rupees(saved.plan.sms.amountMinor)
        val name = saved.plan.merchant.name ?: saved.plan.sms.payee?.trim()?.takeIf { it.isNotEmpty() }
        val where = name?.let { if (saved.plan.type == SmsEntryType.INCOME) " from $it" else " at $it" }.orEmpty()
        return "Saved $amount$where"
    }

    fun text(saved: AutoSaved): String =
        listOfNotNull(saved.plan.account?.let(RecurringLabels::account), saved.categoryName ?: "No category").joinToString(" · ")

    fun reviewTitle(count: Int): String = if (count == 1) "1 bank SMS to review" else "$count bank SMS to review"

    /** "Added 42 entries from earlier SMS" · "7 to review · tap to check them". */
    fun importTitle(saved: Int): String = when (saved) {
        0 -> "No entries added from earlier SMS"
        1 -> "Added 1 entry from earlier SMS"
        else -> "Added $saved entries from earlier SMS"
    }

    fun importText(toReview: Int): String = when (toReview) {
        0 -> "Nothing waiting for review"
        else -> "$toReview to review · tap to check them"
    }

    internal fun postSaved(context: Context, saved: List<AutoSaved>) {
        if (saved.isEmpty()) return
        val manager = channel(context)
        saved.forEach { entry ->
            val undo = Intent(ACTION_UNDO).setPackage(context.packageName).putExtra(EXTRA_SMS_ID, entry.smsId)
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_fin_sms)
                .setContentTitle(title(entry))
                .setContentText(text(entry))
                .setContentIntent(open(context, route = null, requestCode = entry.smsId.hashCode()))
                .addAction(
                    0,
                    "Undo",
                    PendingIntent.getBroadcast(context, entry.smsId.hashCode(), undo, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE),
                )
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .build()
            // Without the permission (Android 13+, declined) it's simply not shown; the entry is saved either way.
            runCatching { manager.notify(notificationId(entry.smsId), notification) }
        }
    }

    /** One notification for everything waiting, replaced as the count changes. */
    internal fun postReview(context: Context, count: Int) {
        if (count <= 0) return cancelReview(context)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_fin_sms)
            .setContentTitle(reviewTitle(count))
            .setContentText("Tap to check them")
            .setContentIntent(open(context, FinanceRoute.SmsReview, REVIEW_ID))
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()
        runCatching { channel(context).notify(REVIEW_ID, notification) }
    }

    /** An import's progress, replaced after each run; silent. */
    internal fun postImportProgress(context: Context, read: Int, total: Int) {
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_fin_sms)
            .setContentTitle("Adding earlier bank SMS")
            .setContentText("$read of $total read")
            .setProgress(total, read, false)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .build()
        runCatching { channel(context).notify(IMPORT_ID, notification) }
    }

    /** The import's one summary, in place of its progress. Opens To review when some wait there. */
    internal fun postImportDone(context: Context, saved: Int, toReview: Int) {
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_fin_sms)
            .setContentTitle(importTitle(saved))
            .setContentText(importText(toReview))
            .setContentIntent(open(context, FinanceRoute.SmsReview.takeIf { toReview > 0 }, IMPORT_ID))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()
        runCatching { channel(context).notify(IMPORT_ID, notification) }
    }

    /** To review was opened: the review notification has done its job. */
    fun cancelReview(context: Context) = NotificationManagerCompat.from(context).cancel(REVIEW_ID)

    internal fun cancelSaved(context: Context, smsId: String) = NotificationManagerCompat.from(context).cancel(notificationId(smsId))

    private fun notificationId(smsId: String) = smsId.hashCode()

    private fun channel(context: Context): NotificationManagerCompat {
        val manager = NotificationManagerCompat.from(context)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Bank SMS", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Entries added from bank SMS, and SMS waiting for you to check."
            },
        )
        return manager
    }

    /** Opens the app, at [route] when there is one. */
    private fun open(context: Context, route: FinanceRoute?, requestCode: Int): PendingIntent? {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            route?.let { putExtra(FinanceReminders.EXTRA_OPEN_ROUTE, it.encode()) }
        } ?: return null
        return PendingIntent.getActivity(context, requestCode, launch, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
}
