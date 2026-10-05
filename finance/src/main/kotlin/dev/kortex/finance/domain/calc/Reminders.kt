package dev.kortex.finance.domain.calc

import dev.kortex.finance.domain.model.Account
import dev.kortex.finance.domain.model.CardStatement
import dev.kortex.finance.domain.model.Recurring
import dev.kortex.finance.domain.model.RecurringKind
import dev.kortex.finance.domain.model.Transaction
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** A reminder to post today: "Netflix ₹649 due in 2 days". The daily job formats and posts it. */
data class Reminder(
    /** Stable for one occurrence, so posting it twice replaces the first. */
    val key: String,
    val kind: PendingKind,
    /** The recurring payment's name, or the card's. */
    val name: String,
    val amountMinor: Long,
    val dueOn: LocalDate,
    val daysLeft: Int,
    /** An auto-debit: it will be recorded without asking. */
    val automatic: Boolean = false,
)

/** What the daily reminder job posts. It only reads; nothing here writes. */
object Reminders {
    /** Unpaid card bills are reminded this many days before they're due, and again on the day. */
    const val CARD_BILL_DAYS_BEFORE = 3

    fun due(
        today: LocalDate,
        accounts: List<Account>,
        recurring: List<Recurring>,
        statements: List<CardStatement>,
        transactions: List<Transaction>,
    ): List<Reminder> {
        val paid = transactions.mapNotNullTo(HashSet()) { tx -> tx.recurringUid?.let { it to tx.dueOn } }
        val payments = recurring.filter { !it.paused && it.remindDaysBefore != Recurring.NO_REMINDER }.flatMap { payment ->
            RecurringSchedule.occurrencesUntil(payment, today.plusDays(payment.remindDaysBefore.toLong()))
                .filter { dueOn -> (payment.uid to dueOn) !in paid && dueOn.minusDays(payment.remindDaysBefore.toLong()) == today }
                .map { dueOn ->
                    Reminder(
                        key = "rec:${payment.uid}:$dueOn",
                        kind = if (payment.kind == RecurringKind.SUBSCRIPTION) PendingKind.SUBSCRIPTION else PendingKind.FIXED,
                        name = payment.name,
                        amountMinor = payment.amountMinor,
                        dueOn = dueOn,
                        daysLeft = payment.remindDaysBefore,
                        automatic = payment.autoMarkPaid,
                    )
                }
        }
        val names = accounts.associate { it.uid to it.name }
        val bills = Pending.latestPerCard(statements).mapNotNull { statement ->
            val unpaid = Statements.unpaidMinor(statement, transactions)
            val days = ChronoUnit.DAYS.between(today, statement.dueOn).toInt()
            if (unpaid <= 0 || (days != CARD_BILL_DAYS_BEFORE && days != 0)) return@mapNotNull null
            Reminder(
                key = "bill:${statement.uid}:$days",
                kind = PendingKind.CARD_BILL,
                name = names[statement.cardUid] ?: "Your card",
                amountMinor = unpaid,
                dueOn = statement.dueOn,
                daysLeft = days,
            )
        }
        return (payments + bills).sortedBy { it.dueOn }
    }
}
