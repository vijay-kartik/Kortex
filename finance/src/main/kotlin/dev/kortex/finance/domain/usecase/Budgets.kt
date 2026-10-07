package dev.kortex.finance.domain.usecase

import dev.kortex.finance.domain.model.CategoryKind
import dev.kortex.finance.domain.repository.FinanceRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

sealed interface BudgetSaveResult {
    data object Saved : BudgetSaveResult
    data object NotFound : BudgetSaveResult

    /** Only expense categories, built-in or yours, can have a budget. */
    data object NotExpense : BudgetSaveResult

    /** A budget is more than nothing; clearing it is how to stop budgeting a category. */
    data object InvalidAmount : BudgetSaveResult
}

/** Sets or changes a category's standing monthly budget (docs/FINANCE_PLAN.md › Budgets). */
class SetBudget(private val repository: FinanceRepository) {
    suspend operator fun invoke(categoryUid: String, amountMinor: Long): BudgetSaveResult {
        if (amountMinor <= 0) return BudgetSaveResult.InvalidAmount
        val category = repository.getCategory(categoryUid) ?: return BudgetSaveResult.NotFound
        if (category.kind != CategoryKind.EXPENSE) return BudgetSaveResult.NotExpense
        repository.setBudget(categoryUid, amountMinor)
        return BudgetSaveResult.Saved
    }
}

/** The category is no longer budgeted; when none is, the month isn't either. */
class ClearBudget(private val repository: FinanceRepository) {
    suspend operator fun invoke(categoryUid: String) = repository.clearBudget(categoryUid)
}

/** Each budgeted category's monthly amount by its uid, re-emitted whenever a budget changes. */
class ObserveBudgets(private val repository: FinanceRepository) {
    operator fun invoke(): Flow<Map<String, Long>> =
        repository.observeBudgets().map { budgets -> budgets.associate { it.categoryUid to it.amountMinor } }
}
