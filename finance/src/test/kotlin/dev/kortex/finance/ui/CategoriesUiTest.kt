package dev.kortex.finance.ui

import dev.kortex.finance.Fixtures.TODAY
import dev.kortex.finance.Fixtures.tx
import dev.kortex.finance.domain.model.BuiltInCategories
import dev.kortex.finance.domain.model.Category
import dev.kortex.finance.domain.model.CategoryKind
import dev.kortex.finance.domain.model.TransactionType
import dev.kortex.finance.domain.usecase.FinanceSnapshot
import dev.kortex.finance.ui.categories.CategoriesUi
import dev.kortex.finance.ui.categories.CategoryFormState
import dev.kortex.finance.ui.common.BudgetLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CategoriesUiTest {
    private val shopping = Category("shop", "Shopping", CategoryKind.EXPENSE, "Lilac", sortOrder = 3)
    private val health = Category("health", "Health", CategoryKind.EXPENSE, "Mint", sortOrder = 4)
    private val freelance = Category("free", "Freelance", CategoryKind.INCOME, "Sky", sortOrder = 1)

    private val snapshot = FinanceSnapshot(
        accounts = emptyList(),
        transactions = listOf(
            tx(TransactionType.EXPENSE, 100_00, TODAY, "checking", category = "shop"),
            tx(TransactionType.EXPENSE, 200_00, TODAY, "checking", category = "shop"),
            tx(TransactionType.EXPENSE, 300_00, TODAY, "checking", category = "health"),
        ),
        categories = listOf(health, shopping, freelance) + BuiltInCategories.all,
        recurring = emptyList(),
        statements = emptyList(),
    )

    @Test
    fun groupsBuiltInYoursAndIncome() {
        val state = CategoriesUi.build(snapshot, emptyMap(), TODAY)
        assertEquals(listOf("Food", "Travel", "Utilities"), state.expenseBuiltIn.map { it.name })
        assertEquals("Groceries, eating out, delivery", state.expenseBuiltIn.first().subtitle)
        assertEquals(listOf("Shopping", "Health"), state.expenseYours.map { it.name })
        assertEquals("Added by you · 2 entries", state.expenseYours[0].subtitle)
        assertEquals("Added by you · 1 entry", state.expenseYours[1].subtitle)
        assertEquals(listOf("Salary", "Freelance"), state.income.map { it.name })
        assertEquals("Added by you · 0 entries", state.income[1].subtitle)
        assertTrue((state.expenseBuiltIn + state.expenseYours + state.income).all { it.budget == null })
    }

    @Test
    fun builtInExpenseRowsOpenButBuiltInIncomeDoesNot() {
        val state = CategoriesUi.build(snapshot, emptyMap(), TODAY)
        assertTrue(state.expenseBuiltIn.all { it.opens })
        assertTrue(state.expenseYours.all { it.opens })
        assertEquals(listOf(false, true), state.income.map { it.opens })
    }

    @Test
    fun budgetedRowsShowThisMonthAgainstTheBudget() {
        val budgets = mapOf("shop" to 250_00L, "health" to 1_000_00L, BuiltInCategories.Food.uid to 100_00L, "free" to 500_00L)
        val state = CategoriesUi.build(snapshot, budgets, TODAY)
        val (shopping, health) = state.expenseYours
        assertEquals("₹300 of ₹250 this month", shopping.subtitle)
        assertEquals(BudgetLevel.OVER, shopping.budget?.level)
        assertEquals(1f, shopping.budget?.fraction)
        assertEquals("₹300 of ₹1,000 this month", health.subtitle)
        assertEquals(BudgetLevel.UNDER, health.budget?.level)
        assertEquals("₹0 of ₹100 this month", state.expenseBuiltIn.first().subtitle)
        // Income has no budget, and unbudgeted rows keep their subtitle.
        assertNull(state.income[1].budget)
        assertEquals("Cabs, fuel, trains, flights", state.expenseBuiltIn[1].subtitle)
    }

    @Test
    fun budgetLevelsTurnAmberAtEightyAndAlarmPastAHundred() {
        assertEquals(BudgetLevel.UNDER, BudgetLevel.of(79_99, 100_00))
        assertEquals(BudgetLevel.NEAR, BudgetLevel.of(80_00, 100_00))
        assertEquals(BudgetLevel.NEAR, BudgetLevel.of(100_00, 100_00))
        assertEquals(BudgetLevel.OVER, BudgetLevel.of(100_01, 100_00))
    }

    @Test
    fun budgetFieldEmptyIsNoBudgetAndZeroOrThreeDecimalsIsInvalid() {
        val form = CategoryFormState(editUid = BuiltInCategories.Food.uid, builtIn = true, name = "Food", savedBudgetMinor = 8_000_00)
        assertFalse(form.budgetInvalid)
        assertNull(form.typedBudgetMinor)
        assertTrue(form.canSave)
        listOf("0", "12.345", "abc").forEach { text ->
            val invalid = form.copy(budgetText = text)
            assertTrue(text, invalid.budgetInvalid)
            assertFalse(text, invalid.canSave)
        }
        assertEquals(4_000_50L, form.copy(budgetText = "4,000.5").typedBudgetMinor)
    }

    @Test
    fun budgetFieldOnlyAppearsWhenEditingAnExpenseCategory() {
        assertFalse(CategoryFormState(budgetText = "0").budgetable)
        assertFalse(CategoryFormState(editUid = "free", kind = CategoryKind.INCOME, budgetText = "0").budgetInvalid)
        assertTrue(CategoryFormState(editUid = "shop").budgetable)
    }

    @Test
    fun previewShowsTheTypedBudgetOrTheSavedOneWhileInvalid() {
        val form = CategoryFormState(
            editUid = BuiltInCategories.Food.uid,
            builtIn = true,
            name = "Food",
            budgetText = "8000",
            savedBudgetMinor = 8_000_00,
            spentMinor = 5_400_00,
            projectedMinor = 5_586_00,
        )
        val preview = form.preview!!
        assertEquals(68, preview.percentUsed)
        assertEquals("₹2,600 left", preview.left)
        assertEquals("Month ends near ₹5,586", preview.projection)
        assertNull(form.previewNote)

        val over = form.copy(budgetText = "4000").preview!!
        assertEquals("₹1,400 over", over.left)
        assertEquals(BudgetLevel.OVER, over.progress.level)

        val invalid = form.copy(budgetText = "0")
        assertEquals(8_000_00L, invalid.preview?.progress?.budgetMinor)
        assertEquals("Showing the saved budget (₹8,000) until the new amount is valid.", invalid.previewNote)
        assertEquals(
            "Enter an amount above ₹0, with at most two decimals. To stop budgeting Food, use Remove budget.",
            invalid.budgetHelper,
        )

        assertNull(form.copy(budgetText = "").preview)
    }

    @Test
    fun thisMonthIsTheCategorysSpendAndProjection() {
        val (spent, projected) = CategoryFormState.thisMonth(snapshot, shopping, TODAY)
        assertEquals(300_00L, spent)
        // 30 days in September, 29 gone.
        assertEquals(310_34L, projected)
    }

    @Test
    fun moveTargetsAreUncategorisedThenTheSameKindWithoutItself() {
        val targets = CategoryFormState.moveTargets(snapshot, "shop", CategoryKind.EXPENSE)
        assertNull(targets.first().uid)
        assertEquals(listOf("Uncategorised", "Food", "Travel", "Utilities", "Health"), targets.map { it.title })
        assertEquals("Built in", targets[1].subtitle)
    }
}
