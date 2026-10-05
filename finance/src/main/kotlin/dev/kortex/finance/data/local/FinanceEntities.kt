package dev.kortex.finance.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

// Every finance row is keyed by its cloud document id, so references between rows are the same
// uids Firestore uses and sync never has to translate them. `dirty` counts local changes not yet
// pushed (0 when in sync); `updatedAtMillis` is the last change, kept by triggers, and the last
// writer wins on it. Dates are ISO `yyyy-MM-dd` text, which sorts in date order.

@Entity(tableName = "accounts", indices = [Index("last4")])
data class AccountEntity(
    @PrimaryKey val uid: String,
    /** [dev.kortex.finance.domain.model.AccountKind] name. */
    val kind: String,
    val name: String,
    val institution: String?,
    val last4: String?,
    @ColumnInfo(defaultValue = "0") val hasSecret: Boolean,
    val bankType: String?,
    val ifsc: String?,
    val linkedAccountUid: String?,
    val network: String?,
    val expiry: String?,
    val holder: String?,
    val creditLimitMinor: Long?,
    val statementDay: Int?,
    val dueDay: Int?,
    val colorToken: String?,
    @ColumnInfo(defaultValue = "0") val archived: Boolean,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    val dirty: Int = 1,
)

@Entity(
    tableName = "transactions",
    indices = [
        Index("occurredOn"),
        Index("accountUid", "occurredAtMillis"),
        Index("categoryUid", "occurredOn"),
        Index("sourceRef"),
        Index("recurringUid", "dueOn"),
    ],
)
data class TransactionEntity(
    @PrimaryKey val uid: String,
    /** [dev.kortex.finance.domain.model.TransactionType] name. */
    val type: String,
    val amountMinor: Long,
    val currency: String,
    val occurredAtMillis: Long,
    val occurredOn: String,
    /** No foreign keys: deleting an account or category keeps its transactions. */
    val accountUid: String,
    val toAccountUid: String?,
    val categoryUid: String?,
    val merchant: String?,
    val payeeKey: String?,
    val note: String?,
    /** [dev.kortex.finance.domain.model.TransactionSource] name. */
    val source: String,
    val sourceRef: String?,
    val recurringUid: String?,
    val dueOn: String?,
    val statementUid: String?,
    val receiptItemCount: Int?,
    /** JSON array of `{name, quantity, amountMinor}`. */
    val receiptItems: String?,
    val receiptTaxMinor: Long?,
    /** On this device only; never synced as a file. */
    val receiptPhotoPath: String?,
    val receiptPhotoMime: String?,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    val dirty: Int = 1,
)

/** Built-in categories are seeded with fixed uids and `dirty = 0`, and the triggers leave them out. */
@Entity(tableName = "categories")
data class CategoryEntity(
    @PrimaryKey val uid: String,
    val name: String,
    /** [dev.kortex.finance.domain.model.CategoryKind] name. */
    val kind: String,
    val colorToken: String,
    val builtIn: Boolean,
    val sortOrder: Int,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    val dirty: Int = 1,
)

@Entity(tableName = "recurring", indices = [Index("nextDueOn")])
data class RecurringEntity(
    @PrimaryKey val uid: String,
    val name: String,
    /** [dev.kortex.finance.domain.model.RecurringKind] name. */
    val kind: String,
    val amountMinor: Long,
    val currency: String,
    /** [dev.kortex.finance.domain.model.Frequency] name. */
    val frequency: String,
    val interval: Int,
    val anchorDay: Int,
    val nextDueOn: String,
    val accountUid: String,
    val categoryUid: String?,
    val remindDaysBefore: Int,
    val autoMarkPaid: Boolean,
    val paused: Boolean,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    val dirty: Int = 1,
)

@Entity(tableName = "card_statements", indices = [Index("cardUid", "statementOn")])
data class CardStatementEntity(
    @PrimaryKey val uid: String,
    val cardUid: String,
    val periodStart: String,
    val statementOn: String,
    val dueOn: String,
    val totalDueMinor: Long,
    val minDueMinor: Long,
    /** [dev.kortex.finance.domain.model.StatementSource] name. */
    val source: String,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    val dirty: Int = 1,
)

@Entity(tableName = "merchants", indices = [Index(value = ["payeeKey"], unique = true)])
data class MerchantEntity(
    @PrimaryKey val uid: String,
    val payeeKey: String,
    val displayName: String,
    val categoryUid: String?,
    val updatedAtMillis: Long,
    val dirty: Int = 1,
)

/** A row deleted here and not yet pushed as `deleted: true`. Written by the delete triggers only. */
@Entity(tableName = "sync_tombstones", primaryKeys = ["kind", "uid"])
data class SyncTombstoneEntity(
    /** One of the [FinanceSyncSchema] kinds. */
    val kind: String,
    val uid: String,
    val deletedAtMillis: Long,
)

/**
 * One row, id 0. The sync engine sets [applying] inside the transaction where it applies pulled
 * changes, which switches every trigger off, so those rows don't turn dirty and bounce back.
 */
@Entity(tableName = "sync_control")
data class SyncControlEntity(
    @PrimaryKey val id: Int = 0,
    val applying: Boolean = false,
)
