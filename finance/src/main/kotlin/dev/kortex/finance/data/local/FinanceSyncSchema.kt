package dev.kortex.finance.data.local

import androidx.sqlite.db.SupportSQLiteDatabase
import dev.kortex.finance.domain.model.BuiltInCategories

/**
 * SQL for local change tracking, as in Topics (docs/CLOUD_SYNC_PLAN.md › Change tracking): a change
 * to any synced column counts the row dirty and stamps it; a delete leaves a tombstone. Built-in
 * categories are never tracked: every device seeds the same ones.
 */
object FinanceSyncSchema {
    const val KIND_ACCOUNT = "finAccount"
    const val KIND_TRANSACTION = "finTransaction"
    const val KIND_CATEGORY = "finCategory"
    const val KIND_RECURRING = "finRecurring"
    const val KIND_STATEMENT = "finStatement"
    const val KIND_MERCHANT = "finMerchant"
    const val KIND_SECRET = "finSecret"

    private class Tracked(val table: String, val kind: String, val columns: List<String>, val extraWhen: String? = null)

    private val TRACKED = listOf(
        Tracked(
            "accounts", KIND_ACCOUNT,
            listOf(
                "kind", "name", "institution", "last4", "hasSecret", "bankType", "ifsc", "linkedAccountUid", "network",
                "expiry", "holder", "creditLimitMinor", "statementDay", "dueDay", "colorToken", "archived",
            ),
        ),
        Tracked(
            "transactions", KIND_TRANSACTION,
            listOf(
                "type", "amountMinor", "currency", "occurredAtMillis", "occurredOn", "accountUid", "toAccountUid",
                "categoryUid", "merchant", "payeeKey", "note", "source", "sourceRef", "recurringUid", "dueOn",
                "statementUid", "receiptItemCount", "receiptItems", "receiptTaxMinor", "receiptPhotoPath", "receiptPhotoMime",
            ),
        ),
        Tracked("categories", KIND_CATEGORY, listOf("name", "kind", "colorToken", "sortOrder"), extraWhen = "builtIn = 0"),
        Tracked(
            "recurring", KIND_RECURRING,
            listOf(
                "name", "kind", "amountMinor", "currency", "frequency", "interval", "anchorDay", "nextDueOn", "accountUid",
                "categoryUid", "remindDaysBefore", "autoMarkPaid", "paused",
            ),
        ),
        Tracked(
            "card_statements", KIND_STATEMENT,
            listOf("cardUid", "periodStart", "statementOn", "dueOn", "totalDueMinor", "minDueMinor", "source"),
        ),
        Tracked("merchants", KIND_MERCHANT, listOf("payeeKey", "displayName", "categoryUid")),
        Tracked("secrets", KIND_SECRET, listOf("cipherText", "keyVersion")),
    )

    internal fun seedControl(db: SupportSQLiteDatabase) {
        db.execSQL("INSERT OR IGNORE INTO sync_control (id, applying) VALUES (0, 0)")
    }

    /** Safe to run on every open: rows that exist are left alone. */
    internal fun seedBuiltInCategories(db: SupportSQLiteDatabase) {
        BuiltInCategories.all.forEach { category ->
            db.execSQL(
                "INSERT OR IGNORE INTO categories " +
                    "(uid, name, kind, colorToken, builtIn, sortOrder, createdAtMillis, updatedAtMillis, dirty) " +
                    "VALUES (?, ?, ?, ?, 1, ?, 0, 0, 0)",
                arrayOf<Any>(category.uid, category.name, category.kind.name, category.colorToken, category.sortOrder),
            )
        }
    }

    /** All tables' triggers, or only [tables]' (a migration adding a table). */
    internal fun createTriggers(db: SupportSQLiteDatabase, tables: Set<String>? = null) {
        TRACKED.filter { tables == null || it.table in tables }.forEach { tracked ->
            val extraNew = tracked.extraWhen?.let { " AND NEW.$it" }.orEmpty()
            val extraOld = tracked.extraWhen?.let { " AND OLD.$it" }.orEmpty()
            db.execSQL(
                """
                CREATE TRIGGER IF NOT EXISTS sync_${tracked.table}_updated
                AFTER UPDATE OF ${tracked.columns.joinToString { "`$it`" }} ON ${tracked.table}
                WHEN $TRACKING$extraNew AND (${changed(tracked.columns)})
                BEGIN
                    UPDATE ${tracked.table} SET dirty = dirty + 1, updatedAtMillis = $NOW_MILLIS WHERE uid = NEW.uid;
                END
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE TRIGGER IF NOT EXISTS sync_${tracked.table}_deleted
                AFTER DELETE ON ${tracked.table} WHEN $TRACKING$extraOld
                BEGIN
                    INSERT OR REPLACE INTO sync_tombstones (kind, uid, deletedAtMillis) VALUES ('${tracked.kind}', OLD.uid, $NOW_MILLIS);
                END
                """.trimIndent(),
            )
        }
    }

    private fun changed(columns: List<String>) = columns.joinToString(" OR ") { "NEW.`$it` IS NOT OLD.`$it`" }

    private const val TRACKING = "(SELECT applying FROM sync_control WHERE id = 0) = 0"
    private const val NOW_MILLIS = "CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER)"
}
