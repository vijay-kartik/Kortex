package dev.kortex.finance.domain.usecase

import dev.kortex.finance.FakeFinanceRepository
import dev.kortex.finance.domain.model.Budget
import dev.kortex.finance.domain.model.BuiltInCategories
import dev.kortex.finance.domain.model.CategoryKind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class BudgetsTest {
    private val repository = FakeFinanceRepository()
    private val setBudget = SetBudget(repository)
    private val clearBudget = ClearBudget(repository)
    private val addCategory = AddCategory(repository)
    private val updateCategory = UpdateCategory(repository)
    private val deleteCategory = DeleteCategory(repository)

    @Test
    fun `built-in and your expense categories can be budgeted, changed and cleared`() = runTest {
        val shopping = (addCategory("Shopping", CategoryKind.EXPENSE, "Lilac") as CategorySaveResult.Saved).uid

        assertEquals(BudgetSaveResult.Saved, setBudget(BuiltInCategories.Food.uid, 8_000_00))
        assertEquals(BudgetSaveResult.Saved, setBudget(shopping, 3_000_00))
        assertEquals(BudgetSaveResult.Saved, setBudget(shopping, 2_500_00))
        assertEquals(setOf(Budget("food", 8_000_00), Budget(shopping, 2_500_00)), repository.observeBudgets().first().toSet())

        clearBudget(BuiltInCategories.Food.uid)
        assertEquals(listOf(Budget(shopping, 2_500_00)), repository.observeBudgets().first())
    }

    @Test
    fun `income categories, missing ones and amounts of nothing are refused`() = runTest {
        assertEquals(BudgetSaveResult.NotExpense, setBudget(BuiltInCategories.Salary.uid, 1_000_00))
        assertEquals(BudgetSaveResult.NotFound, setBudget("gone", 1_000_00))
        assertEquals(BudgetSaveResult.InvalidAmount, setBudget(BuiltInCategories.Food.uid, 0))
        assertEquals(emptyList<Budget>(), repository.observeBudgets().first())
    }

    @Test
    fun `deleting your category drops its budget, and built-ins stay read-only`() = runTest {
        val shopping = (addCategory("Shopping", CategoryKind.EXPENSE, "Lilac") as CategorySaveResult.Saved).uid
        setBudget(shopping, 3_000_00)
        setBudget(BuiltInCategories.Food.uid, 8_000_00)

        assertEquals(CategoryDeleteResult.Deleted, deleteCategory(shopping, moveTo = null))
        assertEquals(CategorySaveResult.BuiltIn, updateCategory(BuiltInCategories.Food.uid, "Eating out", "Rose"))
        assertEquals(CategoryDeleteResult.BuiltIn, deleteCategory(BuiltInCategories.Food.uid, moveTo = null))
        assertEquals(listOf(Budget("food", 8_000_00)), repository.observeBudgets().first())
    }
}
