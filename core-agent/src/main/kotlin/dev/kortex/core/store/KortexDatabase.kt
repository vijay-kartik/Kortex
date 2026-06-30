package dev.kortex.core.store

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * The on-device store for the ambient pipeline, covering all three layers:
 *  - Layer 1 (temporal log): contacts, signals, cross-medium conversations
 *  - Layer 2 (knowledge graph): entities, mentions, relations
 *  - Layer 3 (vectors / RAG): memories (with embedding blob)
 *  - plus action cards (analysis output)
 *
 * Still version 1 — the DB hasn't been instantiated on any device yet, so adding tables
 * needs no migration. Once it ships, schema changes will bump the version + migrate.
 */
@Database(
    entities = [
        ContactEntity::class,
        SignalEntity::class,
        ConversationEntity::class,
        CardEntity::class,
        MemoryEntity::class,
        EntityEntity::class,
        MentionEntity::class,
        RelationEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class KortexDatabase : RoomDatabase() {
    abstract fun contactDao(): ContactDao
    abstract fun signalDao(): SignalDao
    abstract fun conversationDao(): ConversationDao
    abstract fun cardDao(): CardDao
    abstract fun memoryDao(): MemoryDao
    abstract fun graphEntityDao(): GraphEntityDao
    abstract fun mentionDao(): MentionDao
    abstract fun relationDao(): RelationDao
}
