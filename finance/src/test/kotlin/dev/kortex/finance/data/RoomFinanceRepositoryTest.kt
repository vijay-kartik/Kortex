package dev.kortex.finance.data

import dev.kortex.finance.Fixtures
import dev.kortex.finance.data.local.AccountEntity
import dev.kortex.finance.data.local.BudgetEntity
import dev.kortex.finance.data.local.CardStatementEntity
import dev.kortex.finance.data.local.CategoryEntity
import dev.kortex.finance.data.local.FinanceDao
import dev.kortex.finance.data.local.MerchantEntity
import dev.kortex.finance.data.local.PrivateCategoryEntity
import dev.kortex.finance.data.local.RecurringEntity
import dev.kortex.finance.data.local.SecretEntity
import dev.kortex.finance.data.local.TransactionEntity
import dev.kortex.finance.domain.model.CategoryKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Private categories in [RoomFinanceRepository], against an in-memory [FinanceDao] running its real transactions. */
class RoomFinanceRepositoryTest {
    private val dao = FakeFinanceDao()
    private val repository = RoomFinanceRepository(dao, Fixtures.FixedClock(5_000L))

    private fun category(uid: String, kind: CategoryKind, builtIn: Boolean = false) =
        CategoryEntity(uid, uid, kind.name, "Rose", builtIn, sortOrder = 0, createdAtMillis = 0, updatedAtMillis = 0)

    private suspend fun privateUids() = repository.observePrivateCategories().first()

    @Test
    fun `marking a category private removes its budget`() = runBlocking {
        dao.categories.value = mapOf("gifts" to category("gifts", CategoryKind.EXPENSE))
        repository.setBudget("gifts", 300_000)

        repository.setCategoryPrivate("gifts", true)

        assertEquals(setOf("gifts"), privateUids())
        assertNull(dao.getBudget("gifts"))
        assertEquals(5_000L, dao.privateCategories.value.getValue("gifts").createdAtMillis)
    }

    @Test
    fun `built-in expense categories can be private too`() = runBlocking {
        dao.categories.value = mapOf("food" to category("food", CategoryKind.EXPENSE, builtIn = true))

        repository.setCategoryPrivate("food", true)

        assertEquals(setOf("food"), privateUids())
    }

    @Test
    fun `marking it visible again leaves the budget gone`() = runBlocking {
        dao.categories.value = mapOf("gifts" to category("gifts", CategoryKind.EXPENSE))
        repository.setBudget("gifts", 300_000)
        repository.setCategoryPrivate("gifts", true)

        repository.setCategoryPrivate("gifts", false)

        assertEquals(emptySet<String>(), privateUids())
        assertNull(dao.getBudget("gifts"))
    }

    @Test
    fun `an income category can't be private, and its budget stays`() = runBlocking {
        dao.categories.value = mapOf("salary" to category("salary", CategoryKind.INCOME, builtIn = true))
        dao.budgets.value = mapOf("salary" to BudgetEntity("salary", 100, 0, 0))

        repository.setCategoryPrivate("salary", true)

        assertEquals(emptySet<String>(), privateUids())
        assertEquals(100L, dao.getBudget("salary")?.amountMinor)
    }

    @Test
    fun `a category that isn't here can't be made private`() = runBlocking {
        repository.setCategoryPrivate("missing", true)

        assertEquals(emptySet<String>(), privateUids())
    }

    @Test
    fun `deleting a category removes its private mark`() = runBlocking {
        dao.categories.value = mapOf("gifts" to category("gifts", CategoryKind.EXPENSE))
        repository.setCategoryPrivate("gifts", true)

        repository.deleteCategory("gifts", moveTo = null)

        assertEquals(emptySet<String>(), privateUids())
    }

    /** Only what private categories touch; anything else fails the test. */
    private class FakeFinanceDao : FinanceDao() {
        val categories = MutableStateFlow<Map<String, CategoryEntity>>(emptyMap())
        val budgets = MutableStateFlow<Map<String, BudgetEntity>>(emptyMap())
        val privateCategories = MutableStateFlow<Map<String, PrivateCategoryEntity>>(emptyMap())

        override fun observePrivateCategoryUids(): Flow<List<String>> = privateCategories.map { it.keys.toList() }

        override suspend fun insertPrivateCategory(privateCategory: PrivateCategoryEntity) {
            if (privateCategory.categoryUid !in privateCategories.value) {
                privateCategories.value += privateCategory.categoryUid to privateCategory
            }
        }

        override suspend fun deletePrivateCategory(uid: String) {
            privateCategories.value -= uid
        }

        override suspend fun getCategory(uid: String): CategoryEntity? = categories.value[uid]

        override suspend fun deleteCategory(uid: String) {
            if (categories.value[uid]?.builtIn == false) categories.value -= uid
        }

        override suspend fun moveTransactions(uid: String, moveTo: String?) = Unit

        override suspend fun moveRecurring(uid: String, moveTo: String?) = Unit

        override suspend fun moveMerchants(uid: String, moveTo: String?) = Unit

        override suspend fun getBudget(uid: String): BudgetEntity? = budgets.value[uid]

        override suspend fun upsertBudget(budget: BudgetEntity) {
            budgets.value += budget.uid to budget
        }

        override suspend fun deleteBudget(uid: String) {
            budgets.value -= uid
        }

        override fun observeAccounts(): Flow<List<AccountEntity>> = unused()
        override fun observeTransactions(): Flow<List<TransactionEntity>> = unused()
        override fun observeCategories(): Flow<List<CategoryEntity>> = unused()
        override fun observeRecurring(): Flow<List<RecurringEntity>> = unused()
        override fun observeStatements(): Flow<List<CardStatementEntity>> = unused()
        override fun observeBudgets(): Flow<List<BudgetEntity>> = unused()
        override suspend fun getAccount(uid: String): AccountEntity? = unused()
        override suspend fun getTransaction(uid: String): TransactionEntity? = unused()
        override suspend fun findMerchant(payeeKey: String): MerchantEntity? = unused()
        override suspend fun getRecurring(uid: String): RecurringEntity? = unused()
        override suspend fun getStatement(uid: String): CardStatementEntity? = unused()
        override suspend fun insertAccount(account: AccountEntity) = unused()
        override suspend fun updateAccount(account: AccountEntity) = unused()
        override suspend fun deleteAccountRow(uid: String) = unused()
        override suspend fun deleteSecret(uid: String) = unused()
        override suspend fun getSecret(uid: String): SecretEntity? = unused()
        override suspend fun upsertSecret(secret: SecretEntity) = unused()
        override suspend fun markHasSecret(uid: String) = unused()
        override suspend fun insertTransaction(transaction: TransactionEntity) = unused()
        override suspend fun insertTransactionIfAbsent(transaction: TransactionEntity) = unused()
        override suspend fun updateTransaction(transaction: TransactionEntity) = unused()
        override suspend fun deleteTransaction(uid: String) = unused()
        override suspend fun upsertMerchant(merchant: MerchantEntity) = unused()
        override suspend fun insertCategory(category: CategoryEntity) = unused()
        override suspend fun updateCategory(category: CategoryEntity) = unused()
        override suspend fun upsertRecurring(recurring: RecurringEntity) = unused()
        override suspend fun deleteRecurring(uid: String) = unused()
        override suspend fun upsertStatement(statement: CardStatementEntity) = unused()
        override suspend fun insertStatementsIfAbsent(statements: List<CardStatementEntity>) = unused()
        override suspend fun accountDirty(uid: String): Int? = unused()
        override suspend fun transactionDirty(uid: String): Int? = unused()
        override suspend fun categoryDirty(uid: String): Int? = unused()
        override suspend fun recurringDirty(uid: String): Int? = unused()
        override suspend fun statementDirty(uid: String): Int? = unused()
        override suspend fun merchantDirty(uid: String): Int? = unused()

        private fun unused(): Nothing = error("Not used by private categories")
    }
}
