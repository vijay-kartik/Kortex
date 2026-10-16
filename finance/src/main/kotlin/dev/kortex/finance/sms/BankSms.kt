package dev.kortex.finance.sms

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import dev.kortex.finance.R
import dev.kortex.finance.domain.port.BackgroundSync
import dev.kortex.finance.domain.port.PhoneSmsSource
import dev.kortex.finance.domain.port.NotificationPrivacy
import dev.kortex.finance.domain.repository.SmsInboxRepository
import dev.kortex.finance.domain.usecase.ImportSms
import dev.kortex.finance.domain.usecase.ProcessSmsInbox
import dev.kortex.finance.domain.usecase.ReceiveSms
import dev.kortex.finance.domain.usecase.ResolveInboxSms
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Received SMS → finance entries (docs/SMS_AUTO_PLAN.md, phase 4). The app's `SMS_RECEIVED`
 * receiver hands each broadcast to [receive], which keeps bank-looking SMS in the inbox and starts
 * [BankSmsWorker] to read them. Nothing happens while Settings › "Add bank SMS automatically" is off.
 */
object BankSms {
    private const val TAG = "BankSms"
    internal const val WORK_CHANNEL_ID = "bank-sms-work"

    /** Outlives the receiver; [receive]'s caller keeps the process up with `goAsync` until [onDone]. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Queues the SMS in [intent] and starts reading them. [onDone] runs once they're stored (the
     * receiver's `PendingResult.finish`), so the process isn't killed between broadcast and insert.
     */
    fun receive(context: Context, intent: Intent, onDone: () -> Unit) {
        val deps = entryPoint(context)
        if (!deps.bankSmsStore().settings.value.enabled) return onDone()
        val messages = messagesIn(intent)
        if (messages.isEmpty()) return onDone()
        val receivedAt = System.currentTimeMillis()
        scope.launch {
            try {
                var queued = false
                messages.forEach { (sender, body) -> if (deps.receiveSms()(sender, body, receivedAt)) queued = true }
                if (queued) deps.smsImports().processInbox()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't keep a received SMS", e)
            } finally {
                onDone()
            }
        }
    }

    /**
     * Undo on an auto-saved entry's notification: deletes the entry and closes the notification.
     * [onDone] is the action receiver's `PendingResult.finish`.
     */
    fun undo(context: Context, intent: Intent, onDone: () -> Unit) {
        val smsId = intent.getStringExtra(BankSmsNotifications.EXTRA_SMS_ID) ?: return onDone()
        val deps = entryPoint(context)
        scope.launch {
            try {
                deps.resolveInboxSms().undoAutoSaved(smsId)
                BankSmsNotifications.cancelSaved(context, smsId)
                // The entry may already be in the cloud; its delete has to follow it there.
                deps.backgroundSync().requestPush()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't undo an SMS entry", e)
            } finally {
                onDone()
            }
        }
    }

    /**
     * Reads anything a stopped job left waiting, live or imported; the app calls it on start. Does
     * nothing while off. An import still queued or running is left alone: the process also starts
     * to run its next chunk, and a second run appended to it would count the rest twice over.
     */
    fun catchUp(context: Context) {
        val deps = entryPoint(context)
        if (!deps.bankSmsStore().settings.value.enabled) return
        deps.smsImports().processInbox()
        scope.launch {
            if (deps.smsInbox().pending(imported = true, limit = 1).isNotEmpty()) deps.smsImports().resumeImport()
        }
    }

    /** (sender, body) per SMS. A long SMS arrives in parts, in order; one sender's parts are one message. */
    private fun messagesIn(intent: Intent): List<Pair<String, String>> {
        val parts = runCatching { Telephony.Sms.Intents.getMessagesFromIntent(intent) }.getOrNull().orEmpty().filterNotNull()
        return parts
            .groupBy { it.displayOriginatingAddress ?: it.originatingAddress.orEmpty() }
            .map { (sender, sms) -> sender to sms.joinToString("") { it.displayMessageBody.orEmpty() } }
            .filter { (sender, body) -> sender.isNotBlank() && body.isNotBlank() }
    }

    internal fun entryPoint(context: Context): BankSmsEntryPoint =
        EntryPointAccessors.fromApplication(context.applicationContext, BankSmsEntryPoint::class.java)
}

/** Reads the inbox; Hilt dependencies come through [BankSmsEntryPoint], as for the reminders job. */
class BankSmsWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val deps = BankSms.entryPoint(applicationContext)
        return try {
            val run = deps.processSmsInbox()(autoSave = deps.bankSmsStore().settings.value.autoSave)
            BankSmsNotifications.postSaved(applicationContext, run.saved, deps.notificationPrivacy().redact())
            if (run.saved.isNotEmpty()) deps.backgroundSync().requestPush()
            // The count is everything waiting, not just this run's; only new arrivals post it again.
            if (run.toReview > 0) BankSmsNotifications.postReview(applicationContext, deps.smsInbox().observeToReview().first().size)
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Reading the SMS inbox failed", e)
            // Rows stay PENDING, so a retry, or the next SMS or app start, reads them.
            if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
        }
    }

    /** Before Android 12 an expedited job runs as a foreground service, which needs a notification. */
    override suspend fun getForegroundInfo(): ForegroundInfo {
        NotificationManagerCompat.from(applicationContext).createNotificationChannel(
            NotificationChannel(BankSms.WORK_CHANNEL_ID, "Reading bank SMS", NotificationManager.IMPORTANCE_MIN),
        )
        val notification = NotificationCompat.Builder(applicationContext, BankSms.WORK_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_fin_sms)
            .setContentTitle("Reading a bank SMS")
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setSilent(true)
            .build()
        return ForegroundInfo(NOTIFICATION_ID, notification)
    }

    private companion object {
        const val TAG = "BankSmsWorker"
        const val MAX_ATTEMPTS = 3
        const val NOTIFICATION_ID = 0x5B5
    }
}

/**
 * Settings › Add earlier SMS. The first run reads the phone's SMS since the chosen day into the
 * inbox; each later run reads [CHUNK] of them and queues the next, so a long import never meets a
 * job's time limit. The counts ride along in the input. At the end, one notification says what
 * was added and what waits for review; there's none per entry.
 */
class BankSmsImportWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val deps = BankSms.entryPoint(applicationContext)
        val from = inputData.getLong(KEY_FROM, -1)
        return try {
            if (from >= 0) {
                val kept = deps.importSms()(deps.phoneSms().storedSince(from))
                deps.smsImports().continueImport(workDataOf(KEY_TOTAL to kept))
                return Result.success()
            }
            val run = deps.processSmsInbox()(deps.bankSmsStore().settings.value.autoSave, imported = true, limit = CHUNK)
            val total = inputData.getInt(KEY_TOTAL, 0)
            val read = inputData.getInt(KEY_READ, 0) + run.read
            val saved = inputData.getInt(KEY_SAVED, 0) + run.saved.size
            val toReview = inputData.getInt(KEY_REVIEW, 0) + run.toReview
            if (run.more) {
                BankSmsNotifications.postImportProgress(applicationContext, read, maxOf(total, read))
                deps.smsImports().continueImport(
                    workDataOf(KEY_TOTAL to total, KEY_READ to read, KEY_SAVED to saved, KEY_REVIEW to toReview),
                )
            } else {
                BankSmsNotifications.postImportDone(applicationContext, saved, toReview)
                if (saved > 0) deps.backgroundSync().requestPush()
            }
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: SecurityException) {
            // READ_SMS was taken away: nothing more can be read.
            Log.w(TAG, "No permission to read earlier SMS", e)
            Result.failure()
        } catch (e: Exception) {
            Log.w(TAG, "Reading earlier SMS failed", e)
            if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
        }
    }

    internal companion object {
        const val TAG = "BankSmsImportWorker"
        const val KEY_FROM = "from"
        const val KEY_TOTAL = "total"
        const val KEY_READ = "read"
        const val KEY_SAVED = "saved"
        const val KEY_REVIEW = "review"

        /** Each SMS can take a model call or two; 20 keeps a run to a few minutes at worst. */
        const val CHUNK = 20
        const val MAX_ATTEMPTS = 3
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface BankSmsEntryPoint {
    fun bankSmsStore(): BankSmsStore
    fun receiveSms(): ReceiveSms
    fun processSmsInbox(): ProcessSmsInbox
    fun resolveInboxSms(): ResolveInboxSms
    fun smsInbox(): SmsInboxRepository
    fun backgroundSync(): BackgroundSync
    fun importSms(): ImportSms
    fun notificationPrivacy(): NotificationPrivacy
    fun smsImports(): WorkManagerSmsImportScheduler
    fun phoneSms(): PhoneSmsSource
}
