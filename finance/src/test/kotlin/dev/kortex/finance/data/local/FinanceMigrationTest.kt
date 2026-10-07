package dev.kortex.finance.data.local

import androidx.sqlite.db.SupportSQLiteDatabase
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** No SQLite on the JVM here, so this checks the SQL the migration runs rather than running it. */
class FinanceMigrationTest {
    private val statements = mutableListOf<String>()

    private val db = Proxy.newProxyInstance(
        SupportSQLiteDatabase::class.java.classLoader,
        arrayOf(SupportSQLiteDatabase::class.java),
    ) { _, method, args ->
        require(method.name == "execSQL") { "Unexpected call ${method.name}" }
        statements += args[0] as String
        null
    } as SupportSQLiteDatabase

    @Test
    fun `4 to 5 adds the budgets table and its sync triggers`() {
        FinanceDatabase.MIGRATION_4_5.migrate(db)

        assertEquals(4, FinanceDatabase.MIGRATION_4_5.startVersion)
        assertEquals(5, FinanceDatabase.MIGRATION_4_5.endVersion)
        assertEquals(3, statements.size)
        assertEquals(
            "CREATE TABLE IF NOT EXISTS `budgets` (`uid` TEXT NOT NULL, `amountMinor` INTEGER NOT NULL, " +
                "`createdAtMillis` INTEGER NOT NULL, `updatedAtMillis` INTEGER NOT NULL, `dirty` INTEGER NOT NULL, PRIMARY KEY(`uid`))",
            statements[0],
        )
        val updated = statements.single { "sync_budgets_updated" in it }
        assertTrue("AFTER UPDATE OF `amountMinor` ON budgets" in updated)
        assertTrue("UPDATE budgets SET dirty = dirty + 1" in updated)
        val deleted = statements.single { "sync_budgets_deleted" in it }
        assertTrue("'${FinanceSyncSchema.KIND_BUDGET}', OLD.uid" in deleted)
    }

    @Test
    fun `budget triggers track built-in categories' budgets too`() {
        FinanceDatabase.MIGRATION_4_5.migrate(db)

        statements.drop(1).forEach { assertFalse("builtIn" in it) }
    }

    @Test
    fun `only the budgets triggers are created, so other tables' are left alone`() {
        FinanceDatabase.MIGRATION_4_5.migrate(db)

        statements.drop(1).forEach { assertTrue(" ON budgets" in it) }
    }
}
