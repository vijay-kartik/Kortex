package dev.kortex.finance.domain.port

import kotlinx.coroutines.flow.Flow

/** Starts the background jobs that read bank SMS (docs/SMS_AUTO_PLAN.md). Bound to WorkManager. */
interface SmsImportScheduler {
    /**
     * Settings › Add earlier SMS: reads the SMS already on the phone since [fromMillis] and adds
     * them as live ones are, then says once what came of it. Needs `READ_SMS`. A new import
     * replaces one still going; what that one had kept is read by this one too.
     */
    fun importSince(fromMillis: Long)

    /** An import is reading or waiting to: Settings says so. */
    fun observeImporting(): Flow<Boolean>

    /**
     * Reads the received SMS waiting in the inbox now. A run already going finishes first and this
     * one follows it, so an SMS stored after that run listed the inbox is still read.
     */
    fun processInbox()
}
