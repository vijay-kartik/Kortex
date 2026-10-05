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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
        val state = CategoriesUi.build(snapshot)
        assertEquals(listOf("Food", "Travel", "Utilities"), state.expenseBuiltIn.map { it.name })
        assertEquals("Groceries, eating out, delivery", state.expenseBuiltIn.first().subtitle)
        assertEquals(listOf("Shopping", "Health"), state.expenseYours.map { it.name })
        assertEquals("Added by you · 2 entries", state.expenseYours[0].subtitle)
        assertEquals("Added by you · 1 entry", state.expenseYours[1].subtitle)
        assertEquals(listOf("Salary", "Freelance"), state.income.map { it.name })
        assertEquals("Added by you · 0 entries", state.income[1].subtitle)
    }

    @Test
    fun moveTargetsAreUncategorisedThenTheSameKindWithoutItself() {
        val targets = CategoryFormState.moveTargets(snapshot, "shop", CategoryKind.EXPENSE)
        assertNull(targets.first().uid)
        assertEquals(listOf("Uncategorised", "Food", "Travel", "Utilities", "Health"), targets.map { it.title })
        assertEquals("Built in", targets[1].subtitle)
    }
}
