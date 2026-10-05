package dev.kortex.finance.ui.pending

import dev.kortex.finance.domain.calc.Balances
import dev.kortex.finance.domain.calc.DueUrgency
import dev.kortex.finance.domain.calc.Pending
import dev.kortex.finance.domain.calc.PendingKind
import dev.kortex.finance.domain.usecase.FinanceSnapshot
import dev.kortex.finance.ui.FinanceRoute
import dev.kortex.finance.ui.common.FinanceFormat
import dev.kortex.finance.ui.recurring.RecurringLabels
import java.time.LocalDate
import java.time.YearMonth

enum class PendingFilter(val label: String) { ALL("All"), CARDS("Cards"), SUBS("Subs"), FIXED("Fixed") }

data class PendingRowUi(
    val kind: PendingKind,
    /** The statement's or recurring payment's uid. */
    val sourceUid: String,
    val title: String,
    val subtitle: String,
    val amountMinor: Long,
    val dueOn: LocalDate,
    val urgency: DueUrgency,
    /** A recurring payment whose account was deleted: Mark as paid has to ask for one. */
    val needsAccount: Boolean = false,
) {
    val key: String get() = "$sourceUid:$dueOn"
}

data class PendingGroupUi(val label: String, val rows: List<PendingRowUi>)

/** Pending payments (Figma: Pending payments, Recurring 05). */
data class PendingState(
    val loading: Boolean = true,
    val totalMinor: Long = 0,
    val cardBillsMinor: Long = 0,
    val cardBillCount: Int = 0,
    val recurringMinor: Long = 0,
    val recurringCount: Int = 0,
    val totalBalanceMinor: Long = 0,
    val leftAfterMinor: Long = 0,
    val filter: PendingFilter = PendingFilter.ALL,
    val counts: Map<PendingFilter, Int> = emptyMap(),
    val groups: List<PendingGroupUi> = emptyList(),
)

sealed interface PendingIntent {
    data class Filter(val filter: PendingFilter) : PendingIntent
    data class Open(val row: PendingRowUi) : PendingIntent

    /** Swiped: a recurring payment is marked paid as it is; a card bill opens Pay card bill. */
    data class Swipe(val row: PendingRowUi) : PendingIntent
    data object Manage : PendingIntent
}

sealed interface PendingEffect {
    data class Navigate(val route: FinanceRoute) : PendingEffect
}

object PendingUi {
    fun build(snapshot: FinanceSnapshot, today: LocalDate, filter: PendingFilter = PendingFilter.ALL): PendingState {
        val summary = Pending.summary(today, snapshot.statements, snapshot.recurring, snapshot.transactions)
        val recurringByUid = snapshot.recurring.associateBy { it.uid }
        val statementsByUid = snapshot.statements.associateBy { it.uid }
        val rows = summary.items.map { item ->
            when (item.kind) {
                PendingKind.CARD_BILL -> {
                    val card = statementsByUid[item.sourceUid]?.cardUid?.let(snapshot.accountsByUid::get)
                    PendingRowUi(
                        kind = item.kind,
                        sourceUid = item.sourceUid,
                        title = card?.let(RecurringLabels::account) ?: "Card bill",
                        subtitle = item.minDueMinor?.takeIf { it > 0 }
                            ?.let { "Card bill · min ${FinanceFormat.rupees(it, paise = false)}" } ?: "Card bill",
                        amountMinor = item.amountMinor,
                        dueOn = item.dueOn,
                        urgency = Pending.urgency(item.dueOn, today),
                    )
                }
                else -> {
                    val recurring = recurringByUid[item.sourceUid]
                    val needsAccount = recurring != null && recurring.accountUid !in snapshot.accountsByUid
                    PendingRowUi(
                        kind = item.kind,
                        sourceUid = item.sourceUid,
                        title = item.title,
                        subtitle = if (needsAccount) "Needs an account" else recurring?.let(RecurringLabels::summary).orEmpty(),
                        amountMinor = item.amountMinor,
                        dueOn = item.dueOn,
                        urgency = Pending.urgency(item.dueOn, today),
                        needsAccount = needsAccount,
                    )
                }
            }
        }
        val totalBalance = Balances.totalBalanceMinor(snapshot.accounts, snapshot.transactions)
        return PendingState(
            loading = false,
            totalMinor = summary.totalMinor,
            cardBillsMinor = summary.cardBillsMinor,
            cardBillCount = summary.cardBillCount,
            recurringMinor = summary.recurringMinor,
            recurringCount = summary.recurringCount,
            totalBalanceMinor = totalBalance,
            leftAfterMinor = Pending.leftAfterPendingMinor(totalBalance, summary),
            filter = filter,
            counts = PendingFilter.entries.associateWith { f -> rows.count { it.matches(f) } },
            groups = groups(rows.filter { it.matches(filter) }, today),
        )
    }

    /**
     * Overdue, Next 7 days, then the rest by month: "Later in October" for a month the first week
     * already reaches into, "In November" after that.
     */
    fun groups(rows: List<PendingRowUi>, today: LocalDate): List<PendingGroupUi> {
        val reached = setOf(YearMonth.from(today), YearMonth.from(today.plusDays(Pending.SOON_DAYS)))
        return rows.groupBy { row ->
            when (row.urgency) {
                DueUrgency.OVERDUE -> "Overdue"
                DueUrgency.SOON -> "Next 7 days"
                DueUrgency.LATER -> YearMonth.from(row.dueOn).let { month ->
                    if (month in reached) "Later in ${FinanceFormat.monthName(month)}" else "In ${FinanceFormat.monthName(month)}"
                }
            }
        }.map { (label, list) -> PendingGroupUi(label, list) }
    }

    private fun PendingRowUi.matches(filter: PendingFilter) = when (filter) {
        PendingFilter.ALL -> true
        PendingFilter.CARDS -> kind == PendingKind.CARD_BILL
        PendingFilter.SUBS -> kind == PendingKind.SUBSCRIPTION
        PendingFilter.FIXED -> kind == PendingKind.FIXED
    }
}
