package dev.kortex.finance.domain.repository

import dev.kortex.finance.domain.model.InboxSms
import kotlinx.coroutines.flow.Flow

/** Received bank SMS, on this phone only (docs/SMS_AUTO_PLAN.md › Data). */
interface SmsInboxRepository {
    /** Adds [sms] unless a row with its id is already there; false when one was. */
    suspend fun add(sms: InboxSms): Boolean

    suspend fun get(id: String): InboxSms?

    /** Received and not read yet, oldest first: the live ones, or the [imported] ones; at most [limit]. */
    suspend fun pending(imported: Boolean = false, limit: Int = Int.MAX_VALUE): List<InboxSms>

    /** To review, newest first. */
    fun observeToReview(): Flow<List<InboxSms>>

    suspend fun update(sms: InboxSms)

    /** Removes handled rows received before [millis]; pending and to-review rows stay. */
    suspend fun deleteHandledBefore(millis: Long)

    /** How many SMS are kept, whatever their status. */
    fun observeCount(): Flow<Int>

    /** Forgets every kept SMS. Entries they became stay. */
    suspend fun clear()
}
