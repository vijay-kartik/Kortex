package dev.kortex.finance.sms

import android.content.Context
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.workDataOf
import dev.kortex.finance.domain.port.SmsImportScheduler
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** [SmsImportScheduler] on WorkManager: [BankSmsWorker] for received SMS, [BankSmsImportWorker] for earlier ones. */
class WorkManagerSmsImportScheduler(private val context: Context) : SmsImportScheduler {

    private val workManager get() = WorkManager.getInstance(context)

    override fun importSince(fromMillis: Long) =
        enqueueImport(workDataOf(BankSmsImportWorker.KEY_FROM to fromMillis), ExistingWorkPolicy.REPLACE)

    override fun observeImporting(): Flow<Boolean> =
        workManager.getWorkInfosForUniqueWorkFlow(IMPORT_WORK_NAME).map { infos -> infos.any { !it.state.isFinished } }

    override fun processInbox() {
        val request = OneTimeWorkRequestBuilder<BankSmsWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        workManager.enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    /** The import's next run, with what the runs so far did. */
    internal fun continueImport(progress: Data) = enqueueImport(progress, ExistingWorkPolicy.APPEND_OR_REPLACE)

    /** Reads imported SMS a stopped import left waiting; one still queued or running is left alone. */
    internal fun resumeImport() = enqueueImport(Data.EMPTY, ExistingWorkPolicy.KEEP)

    private fun enqueueImport(input: Data, policy: ExistingWorkPolicy) {
        val request = OneTimeWorkRequestBuilder<BankSmsImportWorker>().setInputData(input).build()
        workManager.enqueueUniqueWork(IMPORT_WORK_NAME, policy, request)
    }

    private companion object {
        const val WORK_NAME = "bank-sms"
        const val IMPORT_WORK_NAME = "bank-sms-import"
    }
}
