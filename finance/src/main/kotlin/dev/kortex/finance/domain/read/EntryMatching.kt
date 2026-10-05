package dev.kortex.finance.domain.read

import dev.kortex.finance.domain.model.Account
import dev.kortex.finance.domain.model.Transaction
import dev.kortex.finance.domain.model.TransactionType
import kotlin.math.abs

/** Which account an SMS or receipt is about, and whether it's already been added. */
object EntryMatching {
    const val SMS_DUPLICATE_MINUTES = 10L
    const val RECEIPT_DUPLICATE_MINUTES = 60L

    /**
     * The account whose last 4 digits these are (Paste SMS 02); null means ask (Paste SMS 08).
     * Cards are preferred for a card SMS, bank accounts for an account one.
     */
    fun accountFor(last4: String?, instrument: Instrument, accounts: List<Account>): Account? {
        if (last4 == null) return null
        val matches = accounts.filter { !it.archived && it.last4 == last4 }
        return when (instrument) {
            Instrument.CARD -> matches.firstOrNull { it.kind.isCard } ?: matches.firstOrNull()
            Instrument.ACCOUNT -> matches.firstOrNull { !it.kind.isCard } ?: matches.firstOrNull()
            Instrument.UNKNOWN -> matches.firstOrNull()
        }
    }

    /**
     * An entry this one probably repeats (Paste SMS 05, Scan receipt 05): the same reference, or
     * the same amount from the same account within [windowMinutes]. When only the day is known
     * ([exactTime] false) the same day counts.
     */
    fun duplicateOf(
        amountMinor: Long,
        accountUid: String?,
        atMillis: Long,
        exactTime: Boolean,
        ref: String?,
        transactions: List<Transaction>,
        windowMinutes: Long,
        dayOf: (Long) -> java.time.LocalDate,
    ): Transaction? {
        val spending = transactions.filter { it.type == TransactionType.EXPENSE || it.type == TransactionType.INCOME || it.type == TransactionType.CARD_PAYMENT }
        ref?.let { r -> spending.firstOrNull { it.sourceRef.equals(r, ignoreCase = true) }?.let { return it } }
        if (accountUid == null) return null
        val day = dayOf(atMillis)
        return spending
            .filter { it.amountMinor == amountMinor && it.accountUid == accountUid }
            .filter { if (exactTime) abs(it.occurredAtMillis - atMillis) <= windowMinutes * 60_000 else it.occurredOn == day }
            .minByOrNull { abs(it.occurredAtMillis - atMillis) }
    }
}
