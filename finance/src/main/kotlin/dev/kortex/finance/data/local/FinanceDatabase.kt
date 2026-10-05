package dev.kortex.finance.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        AccountEntity::class,
        TransactionEntity::class,
        CategoryEntity::class,
        RecurringEntity::class,
        CardStatementEntity::class,
        MerchantEntity::class,
        SyncTombstoneEntity::class,
        SyncControlEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class FinanceDatabase : RoomDatabase() {
    abstract fun financeDao(): FinanceDao

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
    }
}
