package dev.kortex.finance.domain.calc

import dev.kortex.finance.domain.model.Account
import dev.kortex.finance.domain.model.CardStatement
import dev.kortex.finance.domain.model.Category
import dev.kortex.finance.domain.model.Recurring
import dev.kortex.finance.domain.model.RecurringKind
import dev.kortex.finance.domain.model.Transaction
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit

/** A reminder to post today: "Netflix ₹649 due in 2 days". The daily job formats and posts it. */
data class Reminder(
    /** Stable for one occurrence, so posting it twice replaces the first. */
    val key: String,
    val kind: PendingKind,
    /** The recurring payment's name, the card's, or the budgeted category's. */
    val name: String,
    /** For a budget, the month's budget. */
    val amountMinor: Long,
    /** For a budget, today. */
    val dueOn: LocalDate,
    val daysLeft: Int,
    /** An auto-debit: it will be recorded without asking. */
    val automatic: Boolean = false,
    /** Budgets only: the month's spend so far. */
    val spentMinor: Long = 0,
    /** Budgets only: the percentage just reached, [Reminders.BUDGET_NEAR_PERCENT] or 100 (gone over). */
    val threshold: Int = 0,
)

/** What the daily reminder job posts. It only reads; nothing here writes. */
object Reminders {
    /** Unpaid card bills are reminded this many days before they're due, and again on the day. */
    const val CARD_BILL_DAYS_BEFORE = 3

    /** A category budget is reminded once when this much of it is used, and once when it's gone over. */
    const val BUDGET_NEAR_PERCENT = 80

    /** [budgets] maps a category's uid to its monthly amount, as in [Budgets.status]. */
    fun due(
        today: LocalDate,
        accounts: List<Account>,
        recurring: List<Recurring>,
        statements: List<CardStatement>,
        transactions: List<Transaction>,
        categories: List<Category> = emptyList(),
        budgets: Map<String, Long> = emptyMap(),
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
        return (payments + bills + budgets(today, transactions, categories, budgets)).sortedBy { it.dueOn }
    }

    /**
     * The job keeps no state, so "the first day" a threshold is crossed comes from the data alone:
     * [Budgets.status] for this month with the transactions up to yesterday is below it, and with
     * those up to today it's reached. Only this month counts, so on the 1st yesterday's spend is
     * nothing. A day that goes from under 80 % to over budget gets both reminders, and their keys
     * carry the month, so each is posted at most once a month.
     */
    private fun budgets(
        today: LocalDate,
        transactions: List<Transaction>,
        categories: List<Category>,
        budgets: Map<String, Long>,
    ): List<Reminder> {
        val month = YearMonth.from(today)
        fun upTo(day: LocalDate) =
            Budgets.status(transactions.filter { it.occurredOn <= day }, categories, budgets, month, today).categories
        val before = upTo(today.minusDays(1)).associate { it.categoryUid to it.spentMinor }
        return upTo(today).flatMap { row ->
            val yesterday = before[row.categoryUid] ?: 0
            fun crossed(reached: (Long) -> Boolean) = reached(row.spentMinor) && !reached(yesterday)
            listOfNotNull(
                BUDGET_NEAR_PERCENT.takeIf { crossed { it * 100 >= row.budgetMinor * BUDGET_NEAR_PERCENT } },
                100.takeIf { crossed { it > row.budgetMinor } },
            ).map { threshold ->
                Reminder(
                    key = "bud:${row.categoryUid}:$month:$threshold",
                    kind = PendingKind.BUDGET,
                    name = row.name,
                    amountMinor = row.budgetMinor,
                    dueOn = today,
                    daysLeft = 0,
                    spentMinor = row.spentMinor,
                    threshold = threshold,
                )
            }
        }
    }
}
