package dev.kortex.finance.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import dev.kortex.finance.domain.model.BuiltInCategories
import kotlinx.coroutines.flow.Flow

/**
 * The sync engine's view of finance.db (docs/FINANCE_PLAN.md › Firestore): rows to push, and
 * pulled documents applied with change tracking off. Rows are keyed by their document ids, so
 * nothing is translated either way, and a transaction whose account hasn't arrived yet is simply
 * applied: it names the account's uid, which resolves once that document lands.
 */
@Dao
abstract class FinanceSyncDao : FinanceDao() {

    // ── Push ──────────────────────────────────────────────────────────

    @Query("SELECT * FROM accounts WHERE dirty > 0")
    abstract suspend fun dirtyAccounts(): List<AccountEntity>

    @Query("SELECT * FROM transactions WHERE dirty > 0")
    abstract suspend fun dirtyTransactions(): List<TransactionEntity>

    /** Your categories only: built-ins are seeded on every device and never pushed. */
    @Query("SELECT * FROM categories WHERE dirty > 0 AND builtIn = 0")
    abstract suspend fun dirtyCategories(): List<CategoryEntity>

    @Query("SELECT * FROM recurring WHERE dirty > 0")
    abstract suspend fun dirtyRecurring(): List<RecurringEntity>

    @Query("SELECT * FROM card_statements WHERE dirty > 0")
    abstract suspend fun dirtyStatements(): List<CardStatementEntity>

    @Query("SELECT * FROM merchants WHERE dirty > 0")
    abstract suspend fun dirtyMerchants(): List<MerchantEntity>

    @Query("SELECT * FROM secrets WHERE dirty > 0")
    abstract suspend fun dirtySecrets(): List<SecretEntity>

    /** Built-in categories' budgets too: unlike the categories, those are yours. */
    @Query("SELECT * FROM budgets WHERE dirty > 0")
    abstract suspend fun dirtyBudgets(): List<BudgetEntity>

    @Query("SELECT * FROM sync_tombstones")
    abstract suspend fun tombstones(): List<SyncTombstoneEntity>

    // Each marks a pushed row in sync only if it hasn't changed again since it was read: [dirty]
    // is the count read before the push, and any change moves it on.

    @Query("UPDATE accounts SET dirty = 0 WHERE uid = :uid AND dirty = :dirty")
    abstract suspend fun markAccountPushed(uid: String, dirty: Int)

    @Query("UPDATE transactions SET dirty = 0 WHERE uid = :uid AND dirty = :dirty")
    abstract suspend fun markTransactionPushed(uid: String, dirty: Int)

    @Query("UPDATE categories SET dirty = 0 WHERE uid = :uid AND dirty = :dirty")
    abstract suspend fun markCategoryPushed(uid: String, dirty: Int)

    @Query("UPDATE recurring SET dirty = 0 WHERE uid = :uid AND dirty = :dirty")
    abstract suspend fun markRecurringPushed(uid: String, dirty: Int)

    @Query("UPDATE card_statements SET dirty = 0 WHERE uid = :uid AND dirty = :dirty")
    abstract suspend fun markStatementPushed(uid: String, dirty: Int)

    @Query("UPDATE merchants SET dirty = 0 WHERE uid = :uid AND dirty = :dirty")
    abstract suspend fun markMerchantPushed(uid: String, dirty: Int)

    @Query("UPDATE secrets SET dirty = 0 WHERE uid = :uid AND dirty = :dirty")
    abstract suspend fun markSecretPushed(uid: String, dirty: Int)

    @Query("UPDATE budgets SET dirty = 0 WHERE uid = :uid AND dirty = :dirty")
    abstract suspend fun markBudgetPushed(uid: String, dirty: Int)

    /** Drops a pushed tombstone, unless the row was deleted again after it was read. */
    @Query("DELETE FROM sync_tombstones WHERE kind = :kind AND uid = :uid AND deletedAtMillis = :deletedAtMillis")
    abstract suspend fun deleteTombstone(kind: String, uid: String, deletedAtMillis: Long)

    // ── Pull ──────────────────────────────────────────────────────────

    @Transaction
    open suspend fun applyPulledAccounts(rows: List<RemoteRow<AccountEntity>>): FinancePullResult =
        applyRows(FinanceSyncSchema.KIND_ACCOUNT, rows, ::accountVersion, { upsertPulledAccount(it.copy(dirty = 0)) }, ::deleteAccount)

    @Transaction
    open suspend fun applyPulledTransactions(rows: List<RemoteRow<TransactionEntity>>): FinancePullResult =
        applyRows(FinanceSyncSchema.KIND_TRANSACTION, rows, ::transactionVersion, { upsertPulledTransaction(it.copy(dirty = 0)) }, ::deleteTransaction)

    /**
     * Built-in uids are never applied. A name another category of the same kind already has here
     * (both phones added "Groceries") becomes "Groceries (2)", left dirty so the rename goes up.
     */
    @Transaction
    open suspend fun applyPulledCategories(rows: List<RemoteRow<CategoryEntity>>): FinancePullResult =
        applyRows(
            FinanceSyncSchema.KIND_CATEGORY,
            rows.filterNot { BuiltInCategories.isBuiltIn(it.uid) },
            ::categoryVersion,
            { category ->
                val name = uniqueCategoryName(category.name, namesOfOtherCategories(category.uid, category.kind))
                upsertPulledCategory(category.copy(name = name, builtIn = false, dirty = if (name == category.name) 0 else 1))
            },
            ::deleteCategory,
        )

    @Transaction
    open suspend fun applyPulledRecurring(rows: List<RemoteRow<RecurringEntity>>): FinancePullResult =
        applyRows(FinanceSyncSchema.KIND_RECURRING, rows, ::recurringVersion, { upsertPulledRecurring(it.copy(dirty = 0)) }, ::deleteRecurring)

    @Transaction
    open suspend fun applyPulledStatements(rows: List<RemoteRow<CardStatementEntity>>): FinancePullResult =
        applyRows(FinanceSyncSchema.KIND_STATEMENT, rows, ::statementVersion, { upsertPulledStatement(it.copy(dirty = 0)) }, ::deleteStatement)

    /** Replaces on a clash of either key, so a merchant's name never ends up under two uids. */
    @Transaction
    open suspend fun applyPulledMerchants(rows: List<RemoteRow<MerchantEntity>>): FinancePullResult =
        applyRows(FinanceSyncSchema.KIND_MERCHANT, rows, ::merchantVersion, { replacePulledMerchant(it.copy(dirty = 0)) }, ::deleteMerchant)

    /** Cipher text only; the account's `hasSecret` arrives with the account itself. */
    @Transaction
    open suspend fun applyPulledSecrets(rows: List<RemoteRow<SecretEntity>>): FinancePullResult =
        applyRows(FinanceSyncSchema.KIND_SECRET, rows, ::secretVersion, { upsertSecret(it.copy(dirty = 0)) }, ::deleteSecret)

    /** Built-in categories' budgets included: their uids are the same on every phone. */
    @Transaction
    open suspend fun applyPulledBudgets(rows: List<RemoteRow<BudgetEntity>>): FinancePullResult =
        applyRows(FinanceSyncSchema.KIND_BUDGET, rows, ::budgetVersion, { upsertBudget(it.copy(dirty = 0)) }, ::deleteBudget)

    /** Applies [rows] with the triggers off; must run inside the caller's transaction. */
    private suspend fun <T> applyRows(
        kind: String,
        rows: List<RemoteRow<T>>,
        version: suspend (String) -> RowVersion?,
        write: suspend (T) -> Unit,
        delete: suspend (String) -> Unit,
    ): FinancePullResult {
        var applied = 0
        var deleted = 0
        var skipped = 0
        setApplying(true)
        for (remote in rows) {
            val local = version(remote.uid)
            val tombstone = if (local == null) findTombstone(kind, remote.uid) else null
            val current = local ?: tombstone?.let { RowVersion(it.deletedAtMillis, dirty = 1) }
            when (decideMerge(current, remote.updatedAtMillis, remote.deleted)) {
                MergeAction.Skip -> skipped++
                MergeAction.Delete -> {
                    if (local != null) delete(remote.uid)
                    tombstone?.let { deleteTombstone(it.kind, it.uid, it.deletedAtMillis) }
                    deleted++
                }
                MergeAction.Apply -> {
                    write(remote.row ?: continue)
                    tombstone?.let { deleteTombstone(it.kind, it.uid, it.deletedAtMillis) }
                    applied++
                }
            }
        }
        setApplying(false)
        return FinancePullResult(applied, deleted, skipped)
    }

    // ── Accounts on this phone ───────────────────────────────────────

    /** Accounts, entries, recurring payments and your categories here: what signing in has to decide about. */
    @Query(
        "SELECT (SELECT COUNT(*) FROM accounts) + (SELECT COUNT(*) FROM transactions) + " +
            "(SELECT COUNT(*) FROM recurring) + (SELECT COUNT(*) FROM categories WHERE builtIn = 0)",
    )
    abstract suspend fun recordCount(): Int

    @Query("SELECT COUNT(*) FROM accounts")
    abstract suspend fun accountCount(): Int

    @Query("SELECT COUNT(*) FROM transactions WHERE type != 'OPENING'")
    abstract suspend fun entryCount(): Int

    /** Local changes the cloud doesn't have yet, unpushed deletes included. */
    @Query(UNPUSHED_COUNT)
    abstract suspend fun unpushedCount(): Int

    /** [unpushedCount], again on every change: live sync pushes when it rises above 0. */
    @Query(UNPUSHED_COUNT)
    abstract fun observeUnpushedCount(): Flow<Int>

    /**
     * Hands this phone's finances to a newly signed-in account: all of it is pushed on the next
     * sync, and deletes made under the previous account are dropped rather than sent to this one.
     */
    @Transaction
    open suspend fun adoptForNewAccount() {
        markAllDirty()
        deleteAllTombstones()
    }

    /** Removes every finance record from this phone, recording no deletes, so nothing reaches any cloud. */
    @Transaction
    open suspend fun discardAll() {
        setApplying(true)
        deleteAllTransactions()
        deleteAllAccounts()
        deleteAllRecurring()
        deleteAllStatements()
        deleteAllMerchants()
        deleteUserCategories()
        deleteAllSecrets()
        deleteAllBudgets()
        deleteAllTombstones()
        setApplying(false)
    }

    private suspend fun markAllDirty() {
        markAllAccountsDirty()
        markAllTransactionsDirty()
        markUserCategoriesDirty()
        markAllRecurringDirty()
        markAllStatementsDirty()
        markAllMerchantsDirty()
        markAllSecretsDirty()
        markAllBudgetsDirty()
    }

    // ── Helpers ───────────────────────────────────────────────────────

    @Query("UPDATE sync_control SET applying = :applying WHERE id = 0")
    protected abstract suspend fun setApplying(applying: Boolean)

    @Query("SELECT * FROM sync_tombstones WHERE kind = :kind AND uid = :uid")
    protected abstract suspend fun findTombstone(kind: String, uid: String): SyncTombstoneEntity?

    @Query("SELECT updatedAtMillis, dirty FROM accounts WHERE uid = :uid")
    protected abstract suspend fun accountVersion(uid: String): RowVersion?

    @Query("SELECT updatedAtMillis, dirty FROM transactions WHERE uid = :uid")
    protected abstract suspend fun transactionVersion(uid: String): RowVersion?

    @Query("SELECT updatedAtMillis, dirty FROM categories WHERE uid = :uid")
    protected abstract suspend fun categoryVersion(uid: String): RowVersion?

    @Query("SELECT updatedAtMillis, dirty FROM recurring WHERE uid = :uid")
    protected abstract suspend fun recurringVersion(uid: String): RowVersion?

    @Query("SELECT updatedAtMillis, dirty FROM card_statements WHERE uid = :uid")
    protected abstract suspend fun statementVersion(uid: String): RowVersion?

    @Query("SELECT updatedAtMillis, dirty FROM merchants WHERE uid = :uid")
    protected abstract suspend fun merchantVersion(uid: String): RowVersion?

    @Query("SELECT updatedAtMillis, dirty FROM secrets WHERE uid = :uid")
    protected abstract suspend fun secretVersion(uid: String): RowVersion?

    @Query("SELECT updatedAtMillis, dirty FROM budgets WHERE uid = :uid")
    protected abstract suspend fun budgetVersion(uid: String): RowVersion?

    @Query("UPDATE secrets SET dirty = dirty + 1")
    protected abstract suspend fun markAllSecretsDirty()

    @Query("DELETE FROM secrets")
    protected abstract suspend fun deleteAllSecrets()

    @Query("UPDATE budgets SET dirty = dirty + 1")
    protected abstract suspend fun markAllBudgetsDirty()

    @Query("DELETE FROM budgets")
    protected abstract suspend fun deleteAllBudgets()

    @Query("SELECT name FROM categories WHERE uid != :uid AND kind = :kind")
    protected abstract suspend fun namesOfOtherCategories(uid: String, kind: String): List<String>

    @Upsert
    protected abstract suspend fun upsertPulledAccount(account: AccountEntity)

    @Upsert
    protected abstract suspend fun upsertPulledTransaction(transaction: TransactionEntity)

    @Upsert
    protected abstract suspend fun upsertPulledCategory(category: CategoryEntity)

    @Upsert
    protected abstract suspend fun upsertPulledRecurring(recurring: RecurringEntity)

    @Upsert
    protected abstract suspend fun upsertPulledStatement(statement: CardStatementEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun replacePulledMerchant(merchant: MerchantEntity)

    @Query("DELETE FROM card_statements WHERE uid = :uid")
    protected abstract suspend fun deleteStatement(uid: String)

    @Query("DELETE FROM merchants WHERE uid = :uid")
    protected abstract suspend fun deleteMerchant(uid: String)

    // Counts up rather than setting 1, like the triggers, so a push in flight can't mark a row clean.
    @Query("UPDATE accounts SET dirty = dirty + 1")
    protected abstract suspend fun markAllAccountsDirty()

    @Query("UPDATE transactions SET dirty = dirty + 1")
    protected abstract suspend fun markAllTransactionsDirty()

    @Query("UPDATE categories SET dirty = dirty + 1 WHERE builtIn = 0")
    protected abstract suspend fun markUserCategoriesDirty()

    @Query("UPDATE recurring SET dirty = dirty + 1")
    protected abstract suspend fun markAllRecurringDirty()

    @Query("UPDATE card_statements SET dirty = dirty + 1")
    protected abstract suspend fun markAllStatementsDirty()

    @Query("UPDATE merchants SET dirty = dirty + 1")
    protected abstract suspend fun markAllMerchantsDirty()

    @Query("DELETE FROM sync_tombstones")
    protected abstract suspend fun deleteAllTombstones()

    @Query("DELETE FROM transactions")
    protected abstract suspend fun deleteAllTransactions()

    @Query("DELETE FROM accounts")
    protected abstract suspend fun deleteAllAccounts()

    @Query("DELETE FROM recurring")
    protected abstract suspend fun deleteAllRecurring()

    @Query("DELETE FROM card_statements")
    protected abstract suspend fun deleteAllStatements()

    @Query("DELETE FROM merchants")
    protected abstract suspend fun deleteAllMerchants()

    @Query("DELETE FROM categories WHERE builtIn = 0")
    protected abstract suspend fun deleteUserCategories()
}

private const val UNPUSHED_COUNT =
    "SELECT (SELECT COUNT(*) FROM accounts WHERE dirty > 0) + (SELECT COUNT(*) FROM transactions WHERE dirty > 0) + " +
        "(SELECT COUNT(*) FROM categories WHERE dirty > 0 AND builtIn = 0) + (SELECT COUNT(*) FROM recurring WHERE dirty > 0) + " +
        "(SELECT COUNT(*) FROM card_statements WHERE dirty > 0) + (SELECT COUNT(*) FROM merchants WHERE dirty > 0) + " +
        "(SELECT COUNT(*) FROM secrets WHERE dirty > 0) + (SELECT COUNT(*) FROM budgets WHERE dirty > 0) + " +
        "(SELECT COUNT(*) FROM sync_tombstones)"
