package dev.kortex.finance.domain.calc

import dev.kortex.finance.domain.model.CardStatement
import dev.kortex.finance.domain.model.Recurring
import dev.kortex.finance.domain.model.RecurringKind
import dev.kortex.finance.domain.model.Transaction
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** [BUDGET] is only ever a [Reminder]: a budget is never a pending payment. */
enum class PendingKind { CARD_BILL, SUBSCRIPTION, FIXED, BUDGET }

/** How a due date is coloured: Amber within 7 days, Alarm once past due. */
enum class DueUrgency { LATER, SOON, OVERDUE }

/** One row in Pending payments. */
data class PendingItem(
    val kind: PendingKind,
    /** The statement's or recurring payment's uid. */
    val sourceUid: String,
    /** Netflix; for a card bill, the card's uid (the screen shows its name). */
    val title: String,
    val amountMinor: Long,
    val dueOn: LocalDate,
    /** Card bills only. */
    val minDueMinor: Long? = null,
)

data class PendingSummary(
    /** By due date, soonest first. */
    val items: List<PendingItem>,
    val cardBillsMinor: Long,
    val cardBillCount: Int,
    val recurringMinor: Long,
    val recurringCount: Int,
) {
    val totalMinor: Long get() = cardBillsMinor + recurringMinor
    val count: Int get() = items.size
}

/** Pending payments (Figma: Dashboard tile, Pending payments). */
object Pending {
    const val HORIZON_DAYS = 30L
    const val SOON_DAYS = 7L

    /**
     * Each card's unpaid latest bill and every recurring occurrence due within [horizonDays] of [today],
     * including overdue ones. A weekly payment can appear several times.
     */
    fun summary(
        today: LocalDate,
        statements: List<CardStatement>,
        recurring: List<Recurring>,
        transactions: List<Transaction>,
        horizonDays: Long = HORIZON_DAYS,
    ): PendingSummary {
        val until = today.plusDays(horizonDays)
        val bills = latestPerCard(statements).mapNotNull { statement ->
            val unpaid = Statements.unpaidMinor(statement, transactions)
            if (unpaid <= 0 || statement.dueOn.isAfter(until)) return@mapNotNull null
            PendingItem(
                kind = PendingKind.CARD_BILL,
                sourceUid = statement.uid,
                title = statement.cardUid,
                amountMinor = unpaid,
                dueOn = statement.dueOn,
                minDueMinor = (statement.minDueMinor - Statements.paidMinor(statement, transactions)).coerceAtLeast(0),
            )
        }
        val paidOccurrences = transactions.mapNotNullTo(HashSet()) { tx -> tx.recurringUid?.let { it to tx.dueOn } }
        val occurrences = recurring.flatMap { payment ->
            RecurringSchedule.occurrencesUntil(payment, until)
                .filterNot { (payment.uid to it) in paidOccurrences }
                .map { dueOn ->
                    PendingItem(
                        kind = if (payment.kind == RecurringKind.SUBSCRIPTION) PendingKind.SUBSCRIPTION else PendingKind.FIXED,
                        sourceUid = payment.uid,
                        title = payment.name,
                        amountMinor = payment.amountMinor,
                        dueOn = dueOn,
                    )
                }
        }
        return PendingSummary(
            items = (bills + occurrences).sortedWith(compareBy({ it.dueOn }, { it.title })),
            cardBillsMinor = bills.sumOf { it.amountMinor },
            cardBillCount = bills.size,
            recurringMinor = occurrences.sumOf { it.amountMinor },
            recurringCount = occurrences.size,
        )
    }

    /**
     * Each card's latest statement. An older one is never pending on its own: whatever was left
     * unpaid on it was still owed on the next statement day, so the newer bill includes it.
     */
    fun latestPerCard(statements: List<CardStatement>): List<CardStatement> =
        statements.groupBy { it.cardUid }.values.map { list -> list.maxBy { it.statementOn } }

    fun leftAfterPendingMinor(totalBalanceMinor: Long, pending: PendingSummary): Long = totalBalanceMinor - pending.totalMinor

    fun urgency(dueOn: LocalDate, today: LocalDate): DueUrgency = when {
        dueOn.isBefore(today) -> DueUrgency.OVERDUE
        ChronoUnit.DAYS.between(today, dueOn) <= SOON_DAYS -> DueUrgency.SOON
        else -> DueUrgency.LATER
    }
}
