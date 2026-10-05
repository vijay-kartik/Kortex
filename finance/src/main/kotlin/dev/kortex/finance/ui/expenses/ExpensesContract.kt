package dev.kortex.finance.ui.expenses

import dev.kortex.finance.domain.calc.Spending
import dev.kortex.finance.domain.model.Transaction
import dev.kortex.finance.domain.usecase.FinanceSnapshot
import dev.kortex.finance.ui.FinanceRoute
import dev.kortex.finance.ui.common.FinanceFormat
import dev.kortex.finance.ui.dashboard.Insight
import dev.kortex.finance.ui.dashboard.ShareUi
import dev.kortex.finance.ui.dashboard.Tone
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.roundToInt

enum class ExpensesMode(val label: String) { Daily("Daily"), Monthly("Monthly"), Yearly("Yearly") }

/** One expense in Daily's "What did I spend on". */
data class ExpenseRowUi(val uid: String, val title: String, val subtitle: String, val amountMinor: Long, val time: String)

/** Expenses (Figma: expenses-daily, finances-expenses, expenses-yearly). */
data class ExpensesState(
    val loading: Boolean = true,
    val mode: ExpensesMode = ExpensesMode.Monthly,
    val periodTitle: String = "",
    val canGoNext: Boolean = false,
    // Daily
    val isToday: Boolean = true,
    val dayTotalMinor: Long = 0,
    val dayRows: List<ExpenseRowUi> = emptyList(),
    val lastDays: List<Pair<String, Long>> = emptyList(),
    val lastDaysTotalMinor: Long = 0,
    // Monthly
    val month: YearMonth = YearMonth.now(),
    val monthSpentMinor: Long = 0,
    val monthIncomeMinor: Long = 0,
    val standouts: List<Insight> = emptyList(),
    // Yearly
    val year: Int = 0,
    val yearSpentMinor: Long = 0,
    val yearSavingsMinor: Long = 0,
    val yearSavingsPercent: Int? = null,
    val yearShares: List<ShareUi> = emptyList(),
) {
    val monthSavingsMinor: Long get() = monthIncomeMinor - monthSpentMinor
}

sealed interface ExpensesIntent {
    data class SelectMode(val mode: ExpensesMode) : ExpensesIntent
    data object Previous : ExpensesIntent
    data object Next : ExpensesIntent
    data object OpenReport : ExpensesIntent
}

sealed interface ExpensesEffect {
    data class Navigate(val route: FinanceRoute) : ExpensesEffect
}

/** Which day, month or year each mode is showing. */
data class ExpensesPeriod(val mode: ExpensesMode, val day: LocalDate, val month: YearMonth, val year: Int) {
    fun previous() = when (mode) {
        ExpensesMode.Daily -> copy(day = day.minusDays(1))
        ExpensesMode.Monthly -> copy(month = month.minusMonths(1))
        ExpensesMode.Yearly -> copy(year = year - 1)
    }

    /** Never past today. */
    fun next(today: LocalDate) = when (mode) {
        ExpensesMode.Daily -> copy(day = minOf(day.plusDays(1), today))
        ExpensesMode.Monthly -> copy(month = minOf(month.plusMonths(1), YearMonth.from(today)))
        ExpensesMode.Yearly -> copy(year = minOf(year + 1, today.year))
    }

    companion object {
        fun startingAt(today: LocalDate) = ExpensesPeriod(ExpensesMode.Monthly, today, YearMonth.from(today), today.year)
    }
}

object ExpensesUi {
    fun build(snapshot: FinanceSnapshot, today: LocalDate, period: ExpensesPeriod, zone: ZoneId): ExpensesState {
        val txs = snapshot.transactions
        val base = ExpensesState(loading = false, mode = period.mode)
        return when (period.mode) {
            ExpensesMode.Daily -> daily(base, snapshot, today, period.day, zone)
            ExpensesMode.Monthly -> monthly(base, snapshot, today, period.month)
            ExpensesMode.Yearly -> {
                val from = LocalDate.of(period.year, 1, 1)
                val to = if (period.year == today.year) today else LocalDate.of(period.year, 12, 31)
                val summary = Spending.period(txs, from, to)
                base.copy(
                    periodTitle = period.year.toString(),
                    canGoNext = period.year < today.year,
                    year = period.year,
                    yearSpentMinor = summary.spentMinor,
                    yearSavingsMinor = summary.savingsMinor,
                    yearSavingsPercent = summary.savingsRate?.let { (it * 1000).roundToInt() / 10 },
                    yearShares = Spending.whereItWent(txs, snapshot.categories, from, to).map {
                        ShareUi(it.category?.name ?: "Other", it.category?.colorToken, it.percent, it.amountMinor)
                    },
                )
            }
        }
    }

    private fun daily(base: ExpensesState, snapshot: FinanceSnapshot, today: LocalDate, day: LocalDate, zone: ZoneId): ExpensesState {
        val rows = Spending.daily(snapshot.transactions, day).map { row(it, snapshot, zone) }
        val week = Spending.lastDays(snapshot.transactions, day)
        val weekday = if (day == today) "Today" else if (day == today.minusDays(1)) "Yesterday" else day.dayOfWeek.name.take(3).lowercase().replaceFirstChar { it.uppercase() }
        return base.copy(
            periodTitle = "$weekday, ${FinanceFormat.fullDate(day)}",
            canGoNext = day.isBefore(today),
            isToday = day == today,
            dayTotalMinor = rows.sumOf { it.amountMinor },
            dayRows = rows,
            lastDays = week.map { (date, amount) -> date.dayOfWeek.name.take(3) to amount },
            lastDaysTotalMinor = week.sumOf { it.second },
        )
    }

    private fun row(tx: Transaction, snapshot: FinanceSnapshot, zone: ZoneId): ExpenseRowUi {
        val category = tx.categoryUid?.let(snapshot.categoriesByUid::get)
        val account = snapshot.accountsByUid[tx.accountUid]?.name ?: "Deleted account"
        return ExpenseRowUi(
            uid = tx.uid,
            title = tx.merchant ?: tx.note ?: category?.name ?: "Expense",
            subtitle = listOfNotNull(category?.name.takeIf { tx.merchant != null }, account).joinToString(" · "),
            amountMinor = tx.amountMinor,
            time = FinanceFormat.time(tx.occurredAtMillis, zone),
        )
    }

    private fun monthly(base: ExpensesState, snapshot: FinanceSnapshot, today: LocalDate, month: YearMonth): ExpensesState {
        val txs = snapshot.transactions
        val current = month == YearMonth.from(today)
        val to = if (current) today else month.atEndOfMonth()
        val summary = Spending.period(txs, month.atDay(1), to)
        return base.copy(
            periodTitle = FinanceFormat.monthYear(month),
            canGoNext = !current,
            month = month,
            monthSpentMinor = summary.spentMinor,
            monthIncomeMinor = summary.incomeMinor,
            standouts = standouts(snapshot, today, month, to, summary.spentMinor),
        )
    }

    /** "What stood out": the biggest category, against last month, the biggest spend, recurring payments. */
    private fun standouts(snapshot: FinanceSnapshot, today: LocalDate, month: YearMonth, to: LocalDate, spent: Long): List<Insight> {
        if (spent <= 0) return emptyList()
        val txs = snapshot.transactions
        val from = month.atDay(1)
        return buildList {
            Spending.whereItWent(txs, snapshot.categories, from, to).firstOrNull { it.category != null }?.let { top ->
                add(Insight("${top.category!!.name} was ", "${top.percent}%", " of what you spent — ${FinanceFormat.rupees(top.amountMinor)}.", Tone.NEUTRAL))
            }
            val last = month.minusMonths(1)
            val lastSpent = if (to == today) Spending.monthToDate(txs, today).lastMonthMinor else Spending.month(txs, last).spentMinor
            if (lastSpent > 0) {
                val change = ((spent - lastSpent) * 100.0 / lastSpent).roundToInt()
                val by = if (to == today) " by the same date." else "."
                when {
                    change < 0 -> add(Insight("", "${abs(change)}% less", " than ${FinanceFormat.monthName(last)}$by", Tone.GOOD))
                    change > 0 -> add(Insight("", "${abs(change)}% more", " than ${FinanceFormat.monthName(last)}$by", Tone.WARN))
                    else -> add(Insight("", "The same", " as ${FinanceFormat.monthName(last)}$by", Tone.NEUTRAL))
                }
            }
            Spending.expenses(txs, from, to).maxByOrNull { it.amountMinor }?.let { biggest ->
                val what = biggest.merchant ?: biggest.note ?: biggest.categoryUid?.let(snapshot.categoriesByUid::get)?.name ?: "an expense"
                add(Insight("Biggest single spend: $what, ", FinanceFormat.rupees(biggest.amountMinor, paise = false), " on ${FinanceFormat.day(biggest.occurredOn)}.", Tone.NEUTRAL))
            }
            val recurring = Spending.expenses(txs, from, to).filter { it.recurringUid != null }
            if (recurring.isNotEmpty()) {
                val noun = if (recurring.size == 1) "recurring payment" else "recurring payments"
                add(Insight("${recurring.size} $noun made for ", FinanceFormat.rupees(recurring.sumOf { it.amountMinor }, paise = false), ".", Tone.NEUTRAL))
            }
        }
    }
}

/** Monthly report (Figma: finances-monthly-report). */
data class MonthlyReportState(
    val loading: Boolean = true,
    val title: String = "",
    val shares: List<ShareUi> = emptyList(),
    val incomeMinor: Long = 0,
    val spentMinor: Long = 0,
    val savingsPercent: Int? = null,
    val savingsHeadline: String = "",
    val savingsLine: String = "",
)

object MonthlyReportUi {
    fun build(snapshot: FinanceSnapshot, month: YearMonth, today: LocalDate): MonthlyReportState {
        val to = if (month == YearMonth.from(today)) today else month.atEndOfMonth()
        val summary = Spending.period(snapshot.transactions, month.atDay(1), to)
        val percent = summary.savingsRate?.let { (it * 100).roundToInt() }
        return MonthlyReportState(
            loading = false,
            title = FinanceFormat.monthYear(month),
            shares = Spending.whereItWent(snapshot.transactions, snapshot.categories, month.atDay(1), to).map {
                ShareUi(it.category?.name ?: "Other", it.category?.colorToken, it.percent, it.amountMinor)
            },
            incomeMinor = summary.incomeMinor,
            spentMinor = summary.spentMinor,
            savingsPercent = percent?.coerceAtLeast(0),
            savingsHeadline = when {
                percent == null -> "No income recorded"
                percent >= 30 -> "Healthy savings rate"
                percent >= 10 -> "Steady savings rate"
                percent >= 0 -> "Low savings rate"
                else -> "Spent more than you earned"
            },
            savingsLine = when {
                percent == null -> "Add income to see how much of it you kept."
                summary.savingsMinor >= 0 -> "You saved ${FinanceFormat.rupees(summary.savingsMinor)} of your income this month."
                else -> "You spent ${FinanceFormat.rupees(-summary.savingsMinor)} more than came in."
            },
        )
    }
}
