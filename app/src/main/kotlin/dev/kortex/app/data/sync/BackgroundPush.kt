package dev.kortex.app.data.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import dev.kortex.sync.CloudAccount
import dev.kortex.sync.CloudSync
import java.util.concurrent.TimeUnit

/**
 * Pushes local changes while the app isn't on screen (docs/SMS_AUTO_PLAN.md, phase 7). Live sync
 * only pushes while the app shows; this covers a push that failed as it left the screen, and
 * entries added from bank SMS while it's closed. It waits for a connection, then retries with
 * backoff. Lives here rather than in `:sync`, which stays free of WorkManager.
 */
object BackgroundPush {
    private const val WORK_NAME = "cloud-push"

    /**
     * Queues a push for when there's a connection. One already queued covers this one too: a
     * push sends every unpushed change, whenever it was made.
     */
    fun enqueue(context: Context) {
        val request = OneTimeWorkRequestBuilder<BackgroundPushWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
    }
}

/** Hilt dependencies come through [BackgroundPushEntryPoint], as for the finance jobs. */
class BackgroundPushWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val deps = EntryPointAccessors.fromApplication(applicationContext, BackgroundPushEntryPoint::class.java)
        // Signed out: nothing may be pushed, and the next sign-in settles the data first.
        if (deps.cloudAccount().user.value == null) return Result.success()
        return when {
            deps.cloudSync().pushPending() -> Result.success()
            runAttemptCount < MAX_ATTEMPTS -> Result.retry()
            // Still failing (the data may belong to another account): the next app open syncs.
            else -> Result.failure()
        }
    }

    private companion object {
        const val MAX_ATTEMPTS = 8
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface BackgroundPushEntryPoint {
    fun cloudAccount(): CloudAccount
    fun cloudSync(): CloudSync
}
