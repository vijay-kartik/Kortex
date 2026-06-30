package dev.kortex.core.store

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room rows. These are deliberately flat: every nested domain type (Handle, Attachment,
 * SignalSource, lists, maps) is stored as a JSON string column and enums as their name.
 * That keeps the schema simple and avoids a pile of Room TypeConverters — the JSON
 * encode/decode happens in Mappers.kt at the domain⇄entity boundary.
 */

@Entity(tableName = "contacts")
data class ContactEntity(
    @PrimaryKey val id: String,
    val displayName: String,
    val handlesJson: String,
    val affinity: String,
    val score: Double?,
    /** Last time we saw any signal for this contact — used for recency tie-breaks. */
    val lastInteractionMillis: Long = 0L,
)

@Entity(
    tableName = "signals",
    indices = [Index("contactId"), Index("timestampMillis")],
)
data class SignalEntity(
    @PrimaryKey val id: String,
    val sourceJson: String,
    val kind: String,
    val direction: String,
    val senderHandleJson: String,
    val contactId: String?,
    val content: String,
    val timestampMillis: Long,
    val attachmentsJson: String,
    val nativeThreadId: String?,
    val metadataJson: String,
)

@Entity(
    tableName = "conversations",
    indices = [Index("contactId")],
)
data class ConversationEntity(
    @PrimaryKey val id: String,
    val contactId: String,
    val contactName: String,
    val sourceAppIdsJson: String,
    val signalIdsJson: String,
    val topicsJson: String,
    val summary: String?,
    val firstAtMillis: Long,
    val lastAtMillis: Long,
    val status: String,
)

@Entity(
    tableName = "cards",
    indices = [Index("contactId"), Index("status"), Index("priorityRank")],
)
data class CardEntity(
    @PrimaryKey val id: String,
    val title: String,
    val summary: String,
    val contactId: String,
    val conversationId: String?,
    /** List<CardAction> as JSON (sealed type, serialized polymorphically). */
    val actionsJson: String,
    val priority: String,
    /** Priority.ordinal, so the feed can ORDER BY priority in SQL. */
    val priorityRank: Int,
    val sourceSignalIdsJson: String,
    val createdAtMillis: Long,
    val expiresAtMillis: Long?,
    val status: String,
)

@Entity(
    tableName = "memories",
    indices = [Index("contactId")],
)
data class MemoryEntity(
    @PrimaryKey val id: String,
    val content: String,
    val kind: String,
    val contactId: String?,
    val sourceSignalIdsJson: String,
    val salience: Double,
    val tagsJson: String,
    val createdAtMillis: Long,
    /** Vector (Layer 3 / RAG) as a raw float BLOB; null until indexed. */
    val embeddingBlob: ByteArray?,
    val embeddingModel: String?,
)

// --- Knowledge graph (Layer 2) ---

@Entity(
    tableName = "graph_entities",
    indices = [Index("name"), Index("resolvedContactId")],
)
data class EntityEntity(
    @PrimaryKey val id: String,
    val type: String,
    val name: String,
    val resolvedContactId: String?,
    val normalizedValue: String?,
    val aliasesJson: String,
    val createdAtMillis: Long,
)

@Entity(
    tableName = "graph_mentions",
    indices = [Index("entityId"), Index("signalId"), Index("conversationId")],
)
data class MentionEntity(
    @PrimaryKey val id: String,
    val entityId: String,
    val signalId: String,
    val conversationId: String?,
    val surfaceText: String,
    val startIndex: Int?,
    val endIndex: Int?,
    val confidence: Double,
    val createdAtMillis: Long,
)

@Entity(
    tableName = "graph_relations",
    indices = [Index("fromType", "fromId"), Index("toType", "toId"), Index("type")],
)
data class RelationEntity(
    @PrimaryKey val id: String,
    val type: String,
    val fromType: String,
    val fromId: String,
    val toType: String,
    val toId: String,
    val weight: Double?,
    val metadataJson: String,
    val createdAtMillis: Long,
)
