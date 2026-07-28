package dev.kortex.core.store

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * The on-device store for persisted agent run traces (the Runs tab / [dev.kortex.core.observability.AgentRun]).
 *
 * Still version 1 — the DB hasn't been instantiated on any device yet, so adding tables
 * needs no migration. Once it ships, schema changes will bump the version + migrate.
 */
@Database(
    entities = [
        RunTraceEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class KortexDatabase : RoomDatabase() {
    abstract fun runTraceDao(): RunTraceDao
}
