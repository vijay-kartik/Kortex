package dev.kortex.finance.domain.usecase

import dev.kortex.finance.FakeFinanceRepository
import dev.kortex.finance.Fixtures
import dev.kortex.finance.Fixtures.TODAY
import dev.kortex.finance.domain.model.CategoryKind
import dev.kortex.finance.domain.model.TransactionType.EXPENSE
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CategoriesTest {
    private val repository = FakeFinanceRepository()
    private val addCategory = AddCategory(repository)
    private val updateCategory = UpdateCategory(repository)
    private val deleteCategory = DeleteCategory(repository)

    @Test
    fun `new categories are named uniquely per kind, ignoring case`() = runTest {
        val shopping = addCategory(" Shopping ", CategoryKind.EXPENSE, "Lilac") as CategorySaveResult.Saved
        assertEquals("Shopping", repository.getCategory(shopping.uid)!!.name)
        assertEquals(CategorySaveResult.NameTaken, addCategory("shopping", CategoryKind.EXPENSE, "Rose"))
        assertEquals(CategorySaveResult.NameTaken, addCategory("FOOD", CategoryKind.EXPENSE, "Rose"))
        assertEquals(CategorySaveResult.BlankName, addCategory(" ", CategoryKind.EXPENSE, "Rose"))
        // The same name is fine for the other kind.
        assertEquals(true, addCategory("Shopping", CategoryKind.INCOME, "Sky") is CategorySaveResult.Saved)
    }

    @Test
    fun `built-in categories can't be changed or deleted`() = runTest {
        assertEquals(CategorySaveResult.BuiltIn, updateCategory("food", "Groceries", "Mint"))
        assertEquals(CategoryDeleteResult.BuiltIn, deleteCategory("salary", null))
        assertEquals(4, repository.categories.value.size)
    }

    @Test
    fun `deleting moves entries and never deletes them`() = runTest {
        val shopping = (addCategory("Shopping", CategoryKind.EXPENSE, "Lilac") as CategorySaveResult.Saved).uid
        repository.transactions.value = listOf(Fixtures.tx(EXPENSE, 100_00, TODAY, "kortex", category = shopping))
        assertEquals(CategoryDeleteResult.Deleted, deleteCategory(shopping, "food"))
        assertEquals("food", repository.transactions.value.single().categoryUid)
        assertNull(repository.getCategory(shopping))
    }

    @Test
    fun `deleting to Uncategorised clears the category`() = runTest {
        val health = (addCategory("Health", CategoryKind.EXPENSE, "Mint") as CategorySaveResult.Saved).uid
        repository.transactions.value = listOf(Fixtures.tx(EXPENSE, 100_00, TODAY, "kortex", category = health))
        deleteCategory(health, null)
        assertNull(repository.transactions.value.single().categoryUid)
    }

    @Test
    fun `entries can only move to another category of the same kind`() = runTest {
        val shopping = (addCategory("Shopping", CategoryKind.EXPENSE, "Lilac") as CategorySaveResult.Saved).uid
        assertEquals(CategoryDeleteResult.InvalidTarget, deleteCategory(shopping, "salary"))
        assertEquals(CategoryDeleteResult.InvalidTarget, deleteCategory(shopping, shopping))
        assertEquals(CategoryDeleteResult.InvalidTarget, deleteCategory(shopping, "missing"))
        assertEquals(CategoryDeleteResult.NotFound, deleteCategory("missing", null))
    }

    @Test
    fun `renaming keeps the uid so past entries follow`() = runTest {
        val shopping = (addCategory("Shopping", CategoryKind.EXPENSE, "Lilac") as CategorySaveResult.Saved).uid
        assertEquals(CategorySaveResult.Saved(shopping), updateCategory(shopping, "Groceries", "Mint"))
        assertEquals("Groceries", repository.getCategory(shopping)!!.name)
        assertEquals(CategorySaveResult.NameTaken, updateCategory(shopping, "Travel", "Mint"))
    }
}
