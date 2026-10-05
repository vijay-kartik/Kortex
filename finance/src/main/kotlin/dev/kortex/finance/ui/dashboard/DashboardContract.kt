package dev.kortex.finance.ui.dashboard

import dev.kortex.finance.domain.calc.Balances
import dev.kortex.finance.domain.calc.Pending
import dev.kortex.finance.domain.calc.Spending
import dev.kortex.finance.domain.usecase.FinanceSnapshot
import dev.kortex.finance.ui.FinanceRoute
import dev.kortex.finance.ui.common.FinanceFormat
import dev.kortex.finance.ui.common.FlowBar
import java.time.LocalDate
import java.time.YearMonth

/** A highlighted sentence: [before] [highlight] [after], the highlight in [tone]. */
data class Insight(val before: String, val highlight: String, val after: String, val tone: Tone)

enum class Tone { GOOD, WARN, NEUTRAL }

/** A slice of Where it went, ready to draw. */
data class ShareUi(val label: String, val colorToken: String?, val percent: Int, val amountMinor: Long)

data class PaceUi(
    val thisMonth: List<Long>,
    val lastMonth: List<Long>,
    val daysInMonth: Int,
    val projectedMinor: Long,
    val lastMonthName: String,
    val thisMonthShort: String,
    val lastMonthShort: String,
)

/** Dashboard (Figma: finances-dashboard, final: B's tiles and chart, C's insight and pace). */
data class DashboardState(
    val loading: Boolean = true,
    val hasAccounts: Boolean = false,
    val totalBalanceMinor: Long = 0,
    val accountCount: Int = 0,
    val pendingMinor: Long = 0,
    val pendingCount: Int = 0,
    val flow: List<FlowBar> = emptyList(),
    /** "September so far" */
    val monthName: String = "",
    val monthNetMinor: Long = 0,
    val insights: List<Insight> = emptyList(),
    val pace: PaceUi? = null,
    val shares: List<ShareUi> = emptyList(),
)

sealed interface DashboardIntent {
    data object AddExpense : DashboardIntent
    data object AddIncome : DashboardIntent
    data object AddAccount : DashboardIntent
}

sealed interface DashboardEffect {
    data class Navigate(val route: FinanceRoute) : DashboardEffect
}

/** Builds [DashboardState] from the data; pure, so every number is tested against the Figma screens. */
object DashboardUi {
    fun build(snapshot: FinanceSnapshot, today: LocalDate): DashboardState {
        val txs = snapshot.transactions
        val month = YearMonth.from(today)
        val lastMonth = month.minusMonths(1)
        val pending = Pending.summary(today, snapshot.statements, snapshot.recurring, txs)
        val flows = Spending.monthlyFlows(txs, month)
        val current = flows.last()
        val previous = flows[flows.size - 2]

        val insights = buildList {
            val mtd = Spending.monthToDate(txs, today)
            if (mtd.thisMonthMinor > 0 || mtd.lastMonthMinor > 0) {
                val last = FinanceFormat.monthName(lastMonth)
                val delta = mtd.deltaMinor
                add(
                    when {
                        delta < 0 -> Insight("You’ve spent ", "${FinanceFormat.rupees(-delta, paise = false)} less", " than $last by day ${mtd.day}.", Tone.GOOD)
                        delta > 0 -> Insight("You’ve spent ", "${FinanceFormat.rupees(delta, paise = false)} more", " than $last by day ${mtd.day}.", Tone.WARN)
                        else -> Insight("You’ve spent ", "the same", " as $last by day ${mtd.day}.", Tone.NEUTRAL)
                    },
                )
            }
            current.keptPercent?.let { kept ->
                val name = FinanceFormat.monthName(month)
                val change = previous.keptPercent?.let { kept - it }
                val tail = when {
                    change == null || change == 0 -> "."
                    change > 0 -> " — ${pointsLabel(change)} more than ${FinanceFormat.monthName(lastMonth)}."
                    else -> " — ${pointsLabel(-change)} less than ${FinanceFormat.monthName(lastMonth)}."
                }
                add(
                    if (kept >= 0) Insight("You kept ", "$kept%", " of $name’s income$tail", Tone.GOOD)
                    else Insight("You spent ", "${-kept}% more", " than $name’s income.", Tone.WARN),
                )
            }
        }

        val thisCumulative = Spending.cumulativeByDay(txs, month, today.dayOfMonth)
        val lastCumulative = Spending.cumulativeByDay(txs, lastMonth)
        val pace = if (thisCumulative.lastOrNull() ?: 0 > 0 || lastCumulative.lastOrNull() ?: 0 > 0) {
            PaceUi(
                thisMonth = thisCumulative,
                lastMonth = lastCumulative,
                daysInMonth = month.lengthOfMonth(),
                projectedMinor = Spending.projectedMonthEndMinor(txs, today),
                lastMonthName = FinanceFormat.monthName(lastMonth),
                thisMonthShort = FinanceFormat.monthShort(month).lowercase().replaceFirstChar { it.uppercase() },
                lastMonthShort = FinanceFormat.monthShort(lastMonth).lowercase().replaceFirstChar { it.uppercase() },
            )
        } else {
            null
        }

        return DashboardState(
            loading = false,
            hasAccounts = snapshot.accounts.isNotEmpty(),
            totalBalanceMinor = Balances.totalBalanceMinor(snapshot.accounts, txs),
            accountCount = snapshot.accounts.count { it.kind.countsInTotal && !it.archived },
            pendingMinor = pending.totalMinor,
            pendingCount = pending.count,
            flow = flows.map { FlowBar(FinanceFormat.monthShort(it.month), it.inMinor, it.outMinor, it.month == month) },
            monthName = FinanceFormat.monthName(month),
            monthNetMinor = current.netMinor,
            insights = insights,
            pace = pace,
            shares = Spending.whereItWent(txs, snapshot.categories, month.atDay(1), today).map {
                ShareUi(it.category?.name ?: "Other", it.category?.colorToken, it.percent, it.amountMinor)
            },
        )
    }

    private fun pointsLabel(points: Int) = if (points == 1) "1 point" else "$points points"
}
