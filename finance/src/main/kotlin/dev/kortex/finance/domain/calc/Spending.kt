package dev.kortex.finance.domain.calc

import dev.kortex.finance.domain.model.Category
import dev.kortex.finance.domain.model.Transaction
import dev.kortex.finance.domain.model.TransactionType
import java.time.LocalDate
import java.time.YearMonth

/** One month's bars in Cash flow. */
data class MonthFlow(val month: YearMonth, val inMinor: Long, val outMinor: Long) {
    val netMinor: Long get() = inMinor - outMinor

    /** "You kept 41%": what's left of income, 0–100 rounded; null with no income. */
    val keptPercent: Int? get() = if (inMinor <= 0) null else roundDiv(netMinor * 100, inMinor).toInt()
}

/** Expenses › Monthly / Yearly, and the Monthly report. */
data class PeriodSummary(val spentMinor: Long, val incomeMinor: Long) {
    val savingsMinor: Long get() = incomeMinor - spentMinor

    /** Savings ÷ income; null with no income. */
    val savingsRate: Double? get() = if (incomeMinor <= 0) null else savingsMinor.toDouble() / incomeMinor
}

/** This month so far against last month by the same day: "₹438 less than August by day 29". */
data class MonthToDate(val day: Int, val thisMonthMinor: Long, val lastMonthMinor: Long) {
    /** Negative: spent less than last month. */
    val deltaMinor: Long get() = thisMonthMinor - lastMonthMinor
}

/** A slice of Where it went. [category] is null for Other, which also holds Uncategorised. */
data class CategoryShare(val category: Category?, val amountMinor: Long, val percent: Int)

/**
 * Spending and income totals. Only EXPENSE is spending and only INCOME is income: OPENING,
 * TRANSFER and CARD_PAYMENT move money between your own accounts, so a card bill is never spent twice.
 */
object Spending {

    fun expenses(transactions: List<Transaction>, from: LocalDate, to: LocalDate): List<Transaction> =
        transactions.filter { it.type == TransactionType.EXPENSE && it.occurredOn in from..to }

    fun spentMinor(transactions: List<Transaction>, from: LocalDate, to: LocalDate): Long =
        expenses(transactions, from, to).sumOf { it.amountMinor }

    fun incomeMinor(transactions: List<Transaction>, from: LocalDate, to: LocalDate): Long =
        transactions.filter { it.type == TransactionType.INCOME && it.occurredOn in from..to }.sumOf { it.amountMinor }

    fun period(transactions: List<Transaction>, from: LocalDate, to: LocalDate): PeriodSummary =
        PeriodSummary(spentMinor(transactions, from, to), incomeMinor(transactions, from, to))

    fun month(transactions: List<Transaction>, month: YearMonth): PeriodSummary =
        period(transactions, month.atDay(1), month.atEndOfMonth())

    /** Expenses › Yearly: January 1 up to [today]. */
    fun yearToDate(transactions: List<Transaction>, today: LocalDate): PeriodSummary =
        period(transactions, today.withDayOfYear(1), today)

    /** The Cash flow chart: [months] months ending with [endMonth], oldest first. */
    fun monthlyFlows(transactions: List<Transaction>, endMonth: YearMonth, months: Int = 6): List<MonthFlow> =
        (months - 1 downTo 0).map { back ->
            val month = endMonth.minusMonths(back.toLong())
            val summary = month(transactions, month)
            MonthFlow(month, summary.incomeMinor, summary.spentMinor)
        }

    /** Days 1…N of this month against days 1…min(N, its length) of last month, N being [today]'s day. */
    fun monthToDate(transactions: List<Transaction>, today: LocalDate): MonthToDate {
        val day = today.dayOfMonth
        val lastMonth = YearMonth.from(today).minusMonths(1)
        val lastDay = lastMonth.atDay(minOf(day, lastMonth.lengthOfMonth()))
        return MonthToDate(
            day = day,
            thisMonthMinor = spentMinor(transactions, today.withDayOfMonth(1), today),
            lastMonthMinor = spentMinor(transactions, lastMonth.atDay(1), lastDay),
        )
    }

    /** "At this pace, month ends near ₹3,290": spent so far ÷ days gone × days in the month. */
    fun projectedMonthEndMinor(transactions: List<Transaction>, today: LocalDate): Long {
        val spent = spentMinor(transactions, today.withDayOfMonth(1), today)
        return roundDiv(spent * today.lengthOfMonth(), today.dayOfMonth.toLong())
    }

    /**
     * Where it went for [from]…[to]: the [top] biggest categories by spend, then Other for the rest
     * and Uncategorised. Percentages add up to exactly 100.
     */
    fun whereItWent(
        transactions: List<Transaction>,
        categories: List<Category>,
        from: LocalDate,
        to: LocalDate,
        top: Int = 4,
    ): List<CategoryShare> {
        val byUid = categories.associateBy { it.uid }
        val totals = expenses(transactions, from, to)
            .groupBy { tx -> tx.categoryUid?.takeIf { it in byUid } }
            .mapValues { (_, txs) -> txs.sumOf { it.amountMinor } }
        val named = totals.filterKeys { it != null }.entries.sortedByDescending { it.value }
        val shown = named.take(top)
        val otherMinor = (totals[null] ?: 0) + named.drop(top).sumOf { it.value }
        val slices = shown.map { byUid.getValue(it.key!!) to it.value } +
            (if (otherMinor > 0) listOf(null to otherMinor) else emptyList())
        val percents = roundedPercents(slices.map { it.second })
        return slices.mapIndexed { i, (category, amount) -> CategoryShare(category, amount, percents[i]) }
    }

    /** Expenses › Daily: the day's expenses, newest first. */
    fun daily(transactions: List<Transaction>, day: LocalDate): List<Transaction> =
        expenses(transactions, day, day).sortedByDescending { it.occurredAtMillis }

    /** "Last 7 days": spend per day, oldest first, ending with [today]. */
    fun lastDays(transactions: List<Transaction>, today: LocalDate, days: Int = 7): List<Pair<LocalDate, Long>> =
        (days - 1 downTo 0).map { back ->
            val day = today.minusDays(back.toLong())
            day to spentMinor(transactions, day, day)
        }

    /** "Added by you · 9 entries": transactions per category. */
    fun entriesPerCategory(transactions: List<Transaction>): Map<String, Int> =
        transactions.mapNotNull { it.categoryUid }.groupingBy { it }.eachCount()
}
