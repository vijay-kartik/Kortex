package dev.kortex.finance.domain.model

import java.time.LocalDate

enum class RecurringKind {
    /** Something you can cancel: Netflix, Spotify. */
    SUBSCRIPTION,

    /** Rent, EMIs, utilities. */
    FIXED,
}

enum class Frequency { WEEKLY, MONTHLY, YEARLY }

/**
 * A subscription or fixed expense (Figma: Recurring 01–05). Each occurrence is paid by a
 * transaction carrying [uid] and the occurrence's due day; [nextDueOn] is the first one not yet
 * paid or skipped.
 */
data class Recurring(
    val uid: String,
    val name: String,
    val kind: RecurringKind,
    val amountMinor: Long,
    val currency: String = Transaction.DEFAULT_CURRENCY,
    val frequency: Frequency,
    /** Every [interval] weeks, months or years. */
    val interval: Int = 1,
    /**
     * The day it falls on: day of month (1–31) for monthly and yearly, clamped to short months so a
     * 31st falls on the 30th or 28th; ISO day of week (1 = Monday) for weekly.
     */
    val anchorDay: Int,
    val nextDueOn: LocalDate,
    val accountUid: String,
    val categoryUid: String? = null,
    val remindDaysBefore: Int = 0,
    /** Auto-debits: each occurrence is recorded on its due day without asking. */
    val autoMarkPaid: Boolean = false,
    val paused: Boolean = false,
    val createdAtMillis: Long,
    val updatedAtMillis: Long = createdAtMillis,
)
