package dev.kortex.finance.data

import dev.kortex.finance.data.local.FinanceDao
import dev.kortex.finance.data.local.toDomain
import dev.kortex.finance.data.local.toEntity
import dev.kortex.finance.domain.model.Account
import dev.kortex.finance.domain.model.CardStatement
import dev.kortex.finance.domain.model.Category
import dev.kortex.finance.domain.model.Merchant
import dev.kortex.finance.domain.model.Recurring
import dev.kortex.finance.domain.model.Transaction
import dev.kortex.finance.domain.port.Clock
import dev.kortex.finance.domain.repository.FinanceRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RoomFinanceRepository(
    private val dao: FinanceDao,
    private val clock: Clock,
) : FinanceRepository {

    override fun observeAccounts(): Flow<List<Account>> = dao.observeAccounts().map { rows -> rows.map { it.toDomain() } }

    override fun observeTransactions(): Flow<List<Transaction>> =
        dao.observeTransactions().map { rows -> rows.map { it.toDomain() } }

    override fun observeCategories(): Flow<List<Category>> = dao.observeCategories().map { rows -> rows.map { it.toDomain() } }

    override fun observeRecurring(): Flow<List<Recurring>> = dao.observeRecurring().map { rows -> rows.map { it.toDomain() } }

    override fun observeStatements(): Flow<List<CardStatement>> = dao.observeStatements().map { rows -> rows.map { it.toDomain() } }

    override suspend fun getAccount(uid: String): Account? = dao.getAccount(uid)?.toDomain()

    override suspend fun getTransaction(uid: String): Transaction? = dao.getTransaction(uid)?.toDomain()

    override suspend fun getCategory(uid: String): Category? = dao.getCategory(uid)?.toDomain()

    override suspend fun findMerchant(payeeKey: String): Merchant? = dao.findMerchant(payeeKey)?.toDomain()

    override suspend fun getRecurring(uid: String): Recurring? = dao.getRecurring(uid)?.toDomain()

    override suspend fun getStatement(uid: String): CardStatement? = dao.getStatement(uid)?.toDomain()

    override suspend fun addAccount(account: Account, opening: Transaction?) =
        dao.insertAccountWithOpening(account.toEntity(), opening?.toEntity())

    override suspend fun updateAccount(account: Account) =
        dao.updateAccount(account.copy(updatedAtMillis = clock.nowMillis()).toEntity())

    override suspend fun deleteAccount(uid: String) = dao.deleteAccount(uid)

    override suspend fun addTransaction(transaction: Transaction, merchant: Merchant?) =
        dao.insertTransactionWithMerchant(transaction.toEntity(), merchant?.toEntity())

    override suspend fun updateTransaction(transaction: Transaction) =
        dao.updateTransaction(transaction.copy(updatedAtMillis = clock.nowMillis()).toEntity())

    override suspend fun deleteTransaction(uid: String) = dao.deleteTransaction(uid)

    override suspend fun addCategory(category: Category) = dao.insertCategory(category.toEntity(clock.nowMillis()))

    override suspend fun updateCategory(category: Category) {
        val existing = dao.getCategory(category.uid) ?: return
        if (existing.builtIn) return
        dao.updateCategory(category.toEntity(clock.nowMillis(), createdAtMillis = existing.createdAtMillis))
    }

    override suspend fun deleteCategory(uid: String, moveTo: String?) = dao.deleteCategoryMoving(uid, moveTo)

    override suspend fun upsertRecurring(recurring: Recurring) =
        dao.upsertRecurring(recurring.copy(updatedAtMillis = clock.nowMillis()).toEntity())

    override suspend fun deleteRecurring(uid: String) = dao.deleteRecurring(uid)

    override suspend fun saveRecurringChange(
        recurring: Recurring,
        payment: Transaction?,
        merchant: Merchant?,
        removePaymentUid: String?,
    ) = dao.saveRecurringChange(
        recurring.copy(updatedAtMillis = clock.nowMillis()).toEntity(),
        payment?.toEntity(),
        merchant?.toEntity(),
        removePaymentUid,
    )

    override suspend fun upsertStatement(statement: CardStatement) =
        dao.upsertStatement(statement.copy(updatedAtMillis = clock.nowMillis()).toEntity())

    override suspend fun addStatementsIfAbsent(statements: List<CardStatement>) {
        if (statements.isNotEmpty()) dao.insertStatementsIfAbsent(statements.map { it.toEntity() })
    }
}
