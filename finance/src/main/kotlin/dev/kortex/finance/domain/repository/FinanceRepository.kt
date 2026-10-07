package dev.kortex.finance.domain.repository

import dev.kortex.finance.domain.model.Account
import dev.kortex.finance.domain.model.Budget
import dev.kortex.finance.domain.model.CardStatement
import dev.kortex.finance.domain.model.Category
import dev.kortex.finance.domain.model.Merchant
import dev.kortex.finance.domain.model.Recurring
import dev.kortex.finance.domain.model.Transaction
import dev.kortex.finance.domain.port.SealedSecret
import kotlinx.coroutines.flow.Flow

/**
 * Finance storage. Each write is one database transaction, so a screen never sees half a change
 * and sync pushes it as one (docs/FINANCE_PLAN.md › Firestore).
 */
interface FinanceRepository {
    fun observeAccounts(): Flow<List<Account>>

    /** Every transaction. A person's history is small enough for the calculators to sum in memory. */
    fun observeTransactions(): Flow<List<Transaction>>

    /** Built-in categories first, then yours, each by sort order. */
    fun observeCategories(): Flow<List<Category>>

    fun observeRecurring(): Flow<List<Recurring>>

    fun observeStatements(): Flow<List<CardStatement>>

    /** One per budgeted category; a category with none isn't budgeted. */
    fun observeBudgets(): Flow<List<Budget>>

    suspend fun getAccount(uid: String): Account?

    suspend fun getTransaction(uid: String): Transaction?

    suspend fun getCategory(uid: String): Category?

    suspend fun findMerchant(payeeKey: String): Merchant?

    suspend fun getRecurring(uid: String): Recurring?

    suspend fun getStatement(uid: String): CardStatement?

    /** The account and its OPENING entry (when there is one) are saved together. */
    suspend fun addAccount(account: Account, opening: Transaction?)

    /** Never changes a balance: there's none on an account to change. */
    suspend fun updateAccount(account: Account)

    /** Its transactions stay, still naming it; screens show them as from a deleted account. Its secret goes with it. */
    suspend fun deleteAccount(uid: String)

    /** The account's full number, sealed; marks it as having one. */
    suspend fun saveSecret(accountUid: String, secret: SealedSecret)

    suspend fun getSecret(accountUid: String): SealedSecret?

    /** Saves [transaction], and [merchant] when given (its name and the category picked for it). */
    suspend fun addTransaction(transaction: Transaction, merchant: Merchant? = null)

    suspend fun updateTransaction(transaction: Transaction)

    suspend fun deleteTransaction(uid: String)

    suspend fun addCategory(category: Category)

    suspend fun updateCategory(category: Category)

    /**
     * Moves everything that used [uid] — transactions, recurring payments, remembered merchants —
     * to [moveTo] (null: Uncategorised), then deletes the category.
     */
    suspend fun deleteCategory(uid: String, moveTo: String?)

    suspend fun upsertRecurring(recurring: Recurring)

    suspend fun deleteRecurring(uid: String)

    /**
     * Saves [recurring] together with paying one of its occurrences ([payment], with its
     * [merchant]) or taking a payment back ([removePaymentUid]), as one change: Mark as paid,
     * Skip and their Undo.
     */
    suspend fun saveRecurringChange(
        recurring: Recurring,
        payment: Transaction? = null,
        merchant: Merchant? = null,
        removePaymentUid: String? = null,
    )

    suspend fun upsertStatement(statement: CardStatement)

    /**
     * Saves the statements whose uid isn't saved yet and leaves the rest alone, so the engine never
     * replaces figures an SMS or an edit already set.
     */
    suspend fun addStatementsIfAbsent(statements: List<CardStatement>)

    /** Sets or changes [categoryUid]'s monthly budget. Callers check it's an expense category. */
    suspend fun setBudget(categoryUid: String, amountMinor: Long)

    /** [categoryUid] is no longer budgeted. */
    suspend fun clearBudget(categoryUid: String)
}
