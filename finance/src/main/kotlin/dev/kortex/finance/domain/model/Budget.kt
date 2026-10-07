package dev.kortex.finance.domain.model

/**
 * One standing monthly amount for an expense category, built-in or yours. No budget for a category
 * means it isn't budgeted; the month's overall budget is the sum of the category budgets.
 */
data class Budget(
    val categoryUid: String,
    val amountMinor: Long,
)
