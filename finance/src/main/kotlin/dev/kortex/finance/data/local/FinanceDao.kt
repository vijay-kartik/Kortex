package dev.kortex.finance.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
abstract class FinanceDao {
    @Query("SELECT * FROM accounts ORDER BY createdAtMillis")
    abstract fun observeAccounts(): Flow<List<AccountEntity>>

    @Query("SELECT * FROM transactions ORDER BY occurredAtMillis DESC, createdAtMillis DESC")
    abstract fun observeTransactions(): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM categories ORDER BY builtIn DESC, kind, sortOrder, name")
    abstract fun observeCategories(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM recurring ORDER BY nextDueOn, name")
    abstract fun observeRecurring(): Flow<List<RecurringEntity>>

    @Query("SELECT * FROM card_statements ORDER BY statementOn DESC")
    abstract fun observeStatements(): Flow<List<CardStatementEntity>>

    @Query("SELECT * FROM accounts WHERE uid = :uid")
    abstract suspend fun getAccount(uid: String): AccountEntity?

    @Query("SELECT * FROM transactions WHERE uid = :uid")
    abstract suspend fun getTransaction(uid: String): TransactionEntity?

    @Query("SELECT * FROM categories WHERE uid = :uid")
    abstract suspend fun getCategory(uid: String): CategoryEntity?

    @Query("SELECT * FROM merchants WHERE payeeKey = :payeeKey")
    abstract suspend fun findMerchant(payeeKey: String): MerchantEntity?

    @Query("SELECT * FROM recurring WHERE uid = :uid")
    abstract suspend fun getRecurring(uid: String): RecurringEntity?

    @Query("SELECT * FROM card_statements WHERE uid = :uid")
    abstract suspend fun getStatement(uid: String): CardStatementEntity?

    @Insert
    abstract suspend fun insertAccount(account: AccountEntity)

    @Update
    abstract suspend fun updateAccount(account: AccountEntity)

    /** Its transactions keep naming it (no foreign keys), so history stays intact. */
    @Query("DELETE FROM accounts WHERE uid = :uid")
    abstract suspend fun deleteAccount(uid: String)

    @Insert
    abstract suspend fun insertTransaction(transaction: TransactionEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertTransactionIfAbsent(transaction: TransactionEntity)

    @Update
    abstract suspend fun updateTransaction(transaction: TransactionEntity)

    @Query("DELETE FROM transactions WHERE uid = :uid")
    abstract suspend fun deleteTransaction(uid: String)

    @Upsert
    abstract suspend fun upsertMerchant(merchant: MerchantEntity)

    @Insert
    abstract suspend fun insertCategory(category: CategoryEntity)

    @Update
    abstract suspend fun updateCategory(category: CategoryEntity)

    @Upsert
    abstract suspend fun upsertRecurring(recurring: RecurringEntity)

    @Query("DELETE FROM recurring WHERE uid = :uid")
    abstract suspend fun deleteRecurring(uid: String)

    @Upsert
    abstract suspend fun upsertStatement(statement: CardStatementEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertStatementsIfAbsent(statements: List<CardStatementEntity>)

    @Transaction
    open suspend fun saveRecurringChange(
        recurring: RecurringEntity,
        payment: TransactionEntity?,
        merchant: MerchantEntity?,
        removePaymentUid: String?,
    ) {
        removePaymentUid?.let { deleteTransaction(it) }
        // Ignored when this occurrence was paid meanwhile (the engine and a tap on the same day).
        payment?.let { insertTransactionIfAbsent(it) }
        merchant?.let { upsertMerchant(it) }
        upsertRecurring(recurring)
    }

    /** The account and its opening entry land together, or not at all. */
    @Transaction
    open suspend fun insertAccountWithOpening(account: AccountEntity, opening: TransactionEntity?) {
        insertAccount(account)
        opening?.let { insertTransaction(it) }
    }

    @Transaction
    open suspend fun insertTransactionWithMerchant(transaction: TransactionEntity, merchant: MerchantEntity?) {
        insertTransaction(transaction)
        merchant?.let { upsertMerchant(it) }
    }

    /** Moves everything that used the category to [moveTo] (null: Uncategorised), then deletes it. */
    @Transaction
    open suspend fun deleteCategoryMoving(uid: String, moveTo: String?) {
        moveTransactions(uid, moveTo)
        moveRecurring(uid, moveTo)
        moveMerchants(uid, moveTo)
        deleteCategory(uid)
    }

    @Query("UPDATE transactions SET categoryUid = :moveTo WHERE categoryUid = :uid")
    abstract suspend fun moveTransactions(uid: String, moveTo: String?)

    @Query("UPDATE recurring SET categoryUid = :moveTo WHERE categoryUid = :uid")
    abstract suspend fun moveRecurring(uid: String, moveTo: String?)

    @Query("UPDATE merchants SET categoryUid = :moveTo WHERE categoryUid = :uid")
    abstract suspend fun moveMerchants(uid: String, moveTo: String?)

    @Query("DELETE FROM categories WHERE uid = :uid AND builtIn = 0")
    abstract suspend fun deleteCategory(uid: String)
}
