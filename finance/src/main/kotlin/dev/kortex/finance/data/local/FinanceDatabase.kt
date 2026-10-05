package dev.kortex.finance.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        AccountEntity::class,
        TransactionEntity::class,
        CategoryEntity::class,
        RecurringEntity::class,
        CardStatementEntity::class,
        MerchantEntity::class,
        SecretEntity::class,
        SyncTombstoneEntity::class,
        SyncControlEntity::class,
    ],
    version = 2,
    exportSchema = false,
)
abstract class FinanceDatabase : RoomDatabase() {
    abstract fun financeDao(): FinanceDao

    abstract fun financeSyncDao(): FinanceSyncDao

    companion object {
        /**
         * A fresh database gets its tables from Room; the sync switch row, triggers and built-in
         * categories come from here. Built-ins are seeded again on every open, in case a restore
         * or a bug ever removed one.
         */
        val CALLBACK = object : Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                FinanceSyncSchema.seedControl(db)
                FinanceSyncSchema.seedBuiltInCategories(db)
                FinanceSyncSchema.createTriggers(db)
            }

            override fun onOpen(db: SupportSQLiteDatabase) {
                FinanceSyncSchema.seedBuiltInCategories(db)
            }
        }

        /** Phase 6: encrypted full numbers. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `secrets` (`uid` TEXT NOT NULL, `cipherText` TEXT NOT NULL, " +
                        "`keyVersion` INTEGER NOT NULL, `updatedAtMillis` INTEGER NOT NULL, `dirty` INTEGER NOT NULL, PRIMARY KEY(`uid`))",
                )
                FinanceSyncSchema.createTriggers(db, setOf("secrets"))
            }
        }
    }
}
