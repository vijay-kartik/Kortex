package dev.kortex.core.store

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface ContactDao {
    @Query("SELECT * FROM contacts")
    suspend fun all(): List<ContactEntity>

    @Query("SELECT * FROM contacts WHERE id = :id")
    suspend fun byId(id: String): ContactEntity?

    @Upsert
    suspend fun upsert(contact: ContactEntity)

    @Query("UPDATE contacts SET lastInteractionMillis = :atMillis WHERE id = :id")
    suspend fun touch(id: String, atMillis: Long)
}

@Dao
interface SignalDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(signal: SignalEntity)

    @Query("SELECT * FROM signals WHERE contactId = :contactId ORDER BY timestampMillis")
    suspend fun forContact(contactId: String): List<SignalEntity>
}

@Dao
interface ConversationDao {
    /** One rolling cross-medium conversation per contact (id = "conv_<contactId>"). */
    @Query("SELECT * FROM conversations WHERE contactId = :contactId LIMIT 1")
    suspend fun forContact(contactId: String): ConversationEntity?

    @Query("SELECT * FROM conversations ORDER BY lastAtMillis DESC")
    suspend fun all(): List<ConversationEntity>

    @Upsert
    suspend fun upsert(conversation: ConversationEntity)
}

@Dao
interface CardDao {
    @Upsert
    suspend fun upsert(card: CardEntity)

    @Query("SELECT * FROM cards WHERE id = :id")
    suspend fun byId(id: String): CardEntity?

    @Query("SELECT * FROM cards WHERE contactId = :contactId ORDER BY createdAtMillis DESC")
    suspend fun forContact(contactId: String): List<CardEntity>

    /** Active feed: still-relevant cards, highest priority then newest first. */
    @Query(
        "SELECT * FROM cards WHERE status IN ('NEW', 'SHOWN') " +
            "ORDER BY priorityRank DESC, createdAtMillis DESC",
    )
    suspend fun feed(): List<CardEntity>

    @Query("UPDATE cards SET status = :status WHERE id = :id")
    suspend fun setStatus(id: String, status: String)
}

@Dao
interface MemoryDao {
    @Upsert
    suspend fun upsert(memory: MemoryEntity)

    @Query("SELECT * FROM memories WHERE id = :id")
    suspend fun byId(id: String): MemoryEntity?

    @Query("SELECT * FROM memories WHERE contactId = :contactId ORDER BY salience DESC, createdAtMillis DESC")
    suspend fun forContact(contactId: String): List<MemoryEntity>

    /** Candidates for semantic retrieval — rows that already have a vector. */
    @Query("SELECT * FROM memories WHERE embeddingBlob IS NOT NULL")
    suspend fun withEmbeddings(): List<MemoryEntity>

    @Query("SELECT * FROM memories ORDER BY createdAtMillis DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<MemoryEntity>
}

@Dao
interface GraphEntityDao {
    @Upsert
    suspend fun upsert(entity: EntityEntity)

    @Query("SELECT * FROM graph_entities WHERE id = :id")
    suspend fun byId(id: String): EntityEntity?

    /** Used when resolving a mentioned name to a known/provisional entity. */
    @Query("SELECT * FROM graph_entities WHERE name = :name COLLATE NOCASE")
    suspend fun byName(name: String): List<EntityEntity>

    @Query("SELECT * FROM graph_entities WHERE resolvedContactId = :contactId")
    suspend fun forContact(contactId: String): List<EntityEntity>
}

@Dao
interface MentionDao {
    @Upsert
    suspend fun upsert(mention: MentionEntity)

    @Query("SELECT * FROM graph_mentions WHERE entityId = :entityId")
    suspend fun forEntity(entityId: String): List<MentionEntity>

    @Query("SELECT * FROM graph_mentions WHERE signalId = :signalId")
    suspend fun forSignal(signalId: String): List<MentionEntity>
}

@Dao
interface RelationDao {
    @Upsert
    suspend fun upsert(relation: RelationEntity)

    /** Outgoing edges from a node (graph traversal). */
    @Query("SELECT * FROM graph_relations WHERE fromType = :nodeType AND fromId = :nodeId")
    suspend fun from(nodeType: String, nodeId: String): List<RelationEntity>

    /** Incoming edges to a node. */
    @Query("SELECT * FROM graph_relations WHERE toType = :nodeType AND toId = :nodeId")
    suspend fun to(nodeType: String, nodeId: String): List<RelationEntity>

    @Query("SELECT * FROM graph_relations WHERE type = :type")
    suspend fun byType(type: String): List<RelationEntity>
}
