package dev.kortex.finance.domain.calc

import dev.kortex.finance.domain.model.Category
import dev.kortex.finance.domain.model.CategoryKind
import dev.kortex.finance.domain.model.Transaction
import java.time.LocalDate
import java.time.YearMonth

/** One budgeted expense category's month: "Food ₹3,200 of ₹4,000 · 80%". */
data class CategoryBudget(
    val categoryUid: String,
    val name: String,
    val budgetMinor: Long,
    val spentMinor: Long,
    /** At this pace, what the month ends near; just [spentMinor] once the month is over. */
    val projectedMinor: Long,
) {
    /** Negative: over budget. */
    val leftMinor: Long get() = budgetMinor - spentMinor

    /** Spent ÷ budget, rounded; can go past 100. */
    val percentUsed: Int get() = roundDiv(spentMinor * 100, budgetMinor).toInt()
}

/** The whole month against the sum of the category budgets. */
data class BudgetTotals(val budgetMinor: Long, val spentMinor: Long, val projectedMinor: Long) {
    /** Negative: over budget. */
    val leftMinor: Long get() = budgetMinor - spentMinor

    /** Spent ÷ budget, rounded; can go past 100. */
    val percentUsed: Int get() = roundDiv(spentMinor * 100, budgetMinor).toInt()
}

/** [overall] is null when no category has a budget: the month isn't budgeted. */
data class BudgetSummary(val categories: List<CategoryBudget>, val overall: BudgetTotals?)

/**
 * Category budgets against the month's spend. Spend counts as in [Spending.whereItWent]: expenses
 * only, so transfers and card bills never count. Uncategorised spend and spend in a category with no
 * budget are left out, and the overall budget is the sum of the category budgets.
 */
object Budgets {

    /**
     * [budgets] maps a category's uid to its monthly amount. Budgets for income categories, unknown
     * categories or amounts of zero or less are ignored. Categories come most-used first.
     */
    fun status(
        transactions: List<Transaction>,
        categories: List<Category>,
        budgets: Map<String, Long>,
        month: YearMonth,
        today: LocalDate,
    ): BudgetSummary {
        val budgeted = categories.filter { it.kind == CategoryKind.EXPENSE && (budgets[it.uid] ?: 0) > 0 }
        if (budgeted.isEmpty()) return BudgetSummary(emptyList(), null)
        val expenses = Spending.expenses(transactions, month.atDay(1), month.atEndOfMonth())
        val spent = expenses.groupBy { it.categoryUid }.mapValues { (_, txs) -> txs.sumOf { it.amountMinor } }
        val pace = pace(month, today)
        val paced = expenses.filter { it.occurredOn <= today }
            .groupBy { it.categoryUid }
            .mapValues { (_, txs) -> txs.sumOf { it.amountMinor } }

        fun projected(spentMinor: Long, pacedMinor: Long): Long =
            if (pace == null) spentMinor else maxOf(spentMinor, roundDiv(pacedMinor * pace.first, pace.second.toLong()))

        val rows = budgeted.map { category ->
            val spentMinor = spent[category.uid] ?: 0
            CategoryBudget(
                categoryUid = category.uid,
                name = category.name,
                budgetMinor = budgets.getValue(category.uid),
                spentMinor = spentMinor,
                projectedMinor = projected(spentMinor, paced[category.uid] ?: 0),
            )
        }.sortedWith(
            compareByDescending<CategoryBudget> { it.spentMinor.toDouble() / it.budgetMinor }.thenBy { it.name },
        )
        val overall = BudgetTotals(
            budgetMinor = rows.sumOf { it.budgetMinor },
            spentMinor = rows.sumOf { it.spentMinor },
            projectedMinor = projected(rows.sumOf { it.spentMinor }, budgeted.sumOf { paced[it.uid] ?: 0 }),
        )
        return BudgetSummary(rows, overall)
    }

    /**
     * Days in [month] to days gone, the same pace as [Spending.projectedMonthEndMinor], while [today]
     * is in [month]. Null for a month that's over or hasn't started: nothing left to project.
     */
    private fun pace(month: YearMonth, today: LocalDate): Pair<Int, Int>? =
        if (YearMonth.from(today) == month) month.lengthOfMonth() to today.dayOfMonth else null
}
