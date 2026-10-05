package dev.kortex.finance

import dev.kortex.finance.domain.model.Account
import dev.kortex.finance.domain.model.BuiltInCategories
import dev.kortex.finance.domain.model.CardStatement
import dev.kortex.finance.domain.model.Category
import dev.kortex.finance.domain.model.Merchant
import dev.kortex.finance.domain.model.Recurring
import dev.kortex.finance.domain.model.Transaction
import dev.kortex.finance.domain.repository.FinanceRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** In memory, with the built-in categories seeded as the database does. */
class FakeFinanceRepository : FinanceRepository {
    val accounts = MutableStateFlow<List<Account>>(emptyList())
    val transactions = MutableStateFlow<List<Transaction>>(emptyList())
    val categories = MutableStateFlow(BuiltInCategories.all)
    val recurring = MutableStateFlow<List<Recurring>>(emptyList())
    val statements = MutableStateFlow<List<CardStatement>>(emptyList())
    val merchants = MutableStateFlow<List<Merchant>>(emptyList())

    override fun observeAccounts(): Flow<List<Account>> = accounts
    override fun observeTransactions(): Flow<List<Transaction>> = transactions
    override fun observeCategories(): Flow<List<Category>> = categories
    override fun observeRecurring(): Flow<List<Recurring>> = recurring
    override fun observeStatements(): Flow<List<CardStatement>> = statements

    override suspend fun getAccount(uid: String) = accounts.value.find { it.uid == uid }
    override suspend fun getTransaction(uid: String) = transactions.value.find { it.uid == uid }
    override suspend fun getCategory(uid: String) = categories.value.find { it.uid == uid }
    override suspend fun findMerchant(payeeKey: String) = merchants.value.find { it.payeeKey == payeeKey }

    override suspend fun addAccount(account: Account, opening: Transaction?) {
        accounts.update { it + account }
        opening?.let { tx -> transactions.update { it + tx } }
    }

    override suspend fun updateAccount(account: Account) = accounts.update { list -> list.map { if (it.uid == account.uid) account else it } }
    override suspend fun deleteAccount(uid: String) = accounts.update { list -> list.filterNot { it.uid == uid } }

    override suspend fun addTransaction(transaction: Transaction, merchant: Merchant?) {
        transactions.update { it + transaction }
        merchant?.let { m -> merchants.update { list -> list.filterNot { it.payeeKey == m.payeeKey } + m } }
    }

    override suspend fun updateTransaction(transaction: Transaction) =
        transactions.update { list -> list.map { if (it.uid == transaction.uid) transaction else it } }

    override suspend fun deleteTransaction(uid: String) = transactions.update { list -> list.filterNot { it.uid == uid } }

    override suspend fun addCategory(category: Category) = categories.update { it + category }
    override suspend fun updateCategory(category: Category) = categories.update { list -> list.map { if (it.uid == category.uid) category else it } }

    override suspend fun deleteCategory(uid: String, moveTo: String?) {
        transactions.update { list -> list.map { if (it.categoryUid == uid) it.copy(categoryUid = moveTo) else it } }
        recurring.update { list -> list.map { if (it.categoryUid == uid) it.copy(categoryUid = moveTo) else it } }
        merchants.update { list -> list.map { if (it.categoryUid == uid) it.copy(categoryUid = moveTo) else it } }
        categories.update { list -> list.filterNot { it.uid == uid } }
    }

    override suspend fun upsertRecurring(recurring: Recurring) =
        this.recurring.update { list -> list.filterNot { it.uid == recurring.uid } + recurring }

    override suspend fun deleteRecurring(uid: String) = recurring.update { list -> list.filterNot { it.uid == uid } }

    override suspend fun upsertStatement(statement: CardStatement) =
        statements.update { list -> list.filterNot { it.uid == statement.uid } + statement }
}
