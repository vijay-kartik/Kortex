package dev.kortex.core.store

import dev.kortex.core.ambient.ActionCard
import dev.kortex.core.ambient.Attachment
import dev.kortex.core.ambient.CardAction
import dev.kortex.core.ambient.CardStatus
import dev.kortex.core.ambient.ContactAffinity
import dev.kortex.core.ambient.ContactRef
import dev.kortex.core.ambient.Conversation
import dev.kortex.core.ambient.ConversationStatus
import dev.kortex.core.ambient.Direction
import dev.kortex.core.ambient.Entity
import dev.kortex.core.ambient.EntityType
import dev.kortex.core.ambient.Handle
import dev.kortex.core.ambient.MemoryEntry
import dev.kortex.core.ambient.MemoryKind
import dev.kortex.core.ambient.Mention
import dev.kortex.core.ambient.NodeRef
import dev.kortex.core.ambient.NodeType
import dev.kortex.core.ambient.Priority
import dev.kortex.core.ambient.Relation
import dev.kortex.core.ambient.RelationType
import dev.kortex.core.ambient.Signal
import dev.kortex.core.ambient.SignalKind
import dev.kortex.core.ambient.SignalSource
import java.nio.ByteBuffer
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** JSON encode/decode for nested types stored as TEXT columns (see Entities.kt). */
private val json = Json { ignoreUnknownKeys = true }

// --- Signal ---

fun Signal.toEntity(): SignalEntity = SignalEntity(
    id = id,
    sourceJson = json.encodeToString(source),
    kind = kind.name,
    direction = direction.name,
    senderHandleJson = json.encodeToString(senderHandle),
    contactId = contactId,
    content = content,
    timestampMillis = timestampMillis,
    attachmentsJson = json.encodeToString(attachments),
    nativeThreadId = nativeThreadId,
    metadataJson = json.encodeToString(metadata),
)

fun SignalEntity.toDomain(): Signal = Signal(
    id = id,
    source = json.decodeFromString<SignalSource>(sourceJson),
    kind = SignalKind.valueOf(kind),
    direction = Direction.valueOf(direction),
    senderHandle = json.decodeFromString<Handle>(senderHandleJson),
    contactId = contactId,
    content = content,
    timestampMillis = timestampMillis,
    attachments = json.decodeFromString<List<Attachment>>(attachmentsJson),
    nativeThreadId = nativeThreadId,
    metadata = json.decodeFromString<Map<String, String>>(metadataJson),
)

// --- Contact ---

fun ContactRef.toEntity(lastInteractionMillis: Long = 0L): ContactEntity = ContactEntity(
    id = id,
    displayName = displayName,
    handlesJson = json.encodeToString(handles),
    affinity = affinity.name,
    score = score,
    lastInteractionMillis = lastInteractionMillis,
)

fun ContactEntity.toDomain(): ContactRef = ContactRef(
    id = id,
    displayName = displayName,
    handles = json.decodeFromString<List<Handle>>(handlesJson),
    affinity = ContactAffinity.valueOf(affinity),
    score = score,
)

// --- Conversation ---

fun Conversation.toEntity(): ConversationEntity = ConversationEntity(
    id = id,
    contactId = contactId,
    contactName = contactName,
    sourceAppIdsJson = json.encodeToString(sourceAppIds),
    signalIdsJson = json.encodeToString(signalIds),
    topicsJson = json.encodeToString(topics),
    summary = summary,
    firstAtMillis = firstAtMillis,
    lastAtMillis = lastAtMillis,
    status = status.name,
)

fun ConversationEntity.toDomain(): Conversation = Conversation(
    id = id,
    contactId = contactId,
    contactName = contactName,
    sourceAppIds = json.decodeFromString<Set<String>>(sourceAppIdsJson),
    signalIds = json.decodeFromString<List<String>>(signalIdsJson),
    topics = json.decodeFromString<List<String>>(topicsJson),
    summary = summary,
    firstAtMillis = firstAtMillis,
    lastAtMillis = lastAtMillis,
    status = ConversationStatus.valueOf(status),
)

// --- ActionCard ---

fun ActionCard.toEntity(): CardEntity = CardEntity(
    id = id,
    title = title,
    summary = summary,
    contactId = contactId,
    conversationId = conversationId,
    actionsJson = json.encodeToString(actions),
    priority = priority.name,
    priorityRank = priority.ordinal,
    sourceSignalIdsJson = json.encodeToString(sourceSignalIds),
    createdAtMillis = createdAtMillis,
    expiresAtMillis = expiresAtMillis,
    status = status.name,
)

fun CardEntity.toDomain(): ActionCard = ActionCard(
    id = id,
    title = title,
    summary = summary,
    contactId = contactId,
    conversationId = conversationId,
    actions = json.decodeFromString<List<CardAction>>(actionsJson),
    priority = Priority.valueOf(priority),
    sourceSignalIds = json.decodeFromString<List<String>>(sourceSignalIdsJson),
    createdAtMillis = createdAtMillis,
    expiresAtMillis = expiresAtMillis,
    status = CardStatus.valueOf(status),
)

// --- MemoryEntry (embedding stored as a raw float BLOB) ---

fun MemoryEntry.toEntity(): MemoryEntity = MemoryEntity(
    id = id,
    content = content,
    kind = kind.name,
    contactId = contactId,
    sourceSignalIdsJson = json.encodeToString(sourceSignalIds),
    salience = salience,
    tagsJson = json.encodeToString(tags),
    createdAtMillis = createdAtMillis,
    embeddingBlob = embedding?.toFloatBytes(),
    embeddingModel = embeddingModel,
)

fun MemoryEntity.toDomain(): MemoryEntry = MemoryEntry(
    id = id,
    content = content,
    kind = MemoryKind.valueOf(kind),
    contactId = contactId,
    sourceSignalIds = json.decodeFromString<List<String>>(sourceSignalIdsJson),
    salience = salience,
    tags = json.decodeFromString<List<String>>(tagsJson),
    createdAtMillis = createdAtMillis,
    embedding = embeddingBlob?.toFloatList(),
    embeddingModel = embeddingModel,
)

// --- Knowledge graph: Entity / Mention / Relation ---

fun Entity.toEntity(): EntityEntity = EntityEntity(
    id = id,
    type = type.name,
    name = name,
    resolvedContactId = resolvedContactId,
    normalizedValue = normalizedValue,
    aliasesJson = json.encodeToString(aliases),
    createdAtMillis = createdAtMillis,
)

fun EntityEntity.toDomain(): Entity = Entity(
    id = id,
    type = EntityType.valueOf(type),
    name = name,
    resolvedContactId = resolvedContactId,
    normalizedValue = normalizedValue,
    aliases = json.decodeFromString<List<String>>(aliasesJson),
    createdAtMillis = createdAtMillis,
)

fun Mention.toEntity(): MentionEntity = MentionEntity(
    id = id,
    entityId = entityId,
    signalId = signalId,
    conversationId = conversationId,
    surfaceText = surfaceText,
    startIndex = startIndex,
    endIndex = endIndex,
    confidence = confidence,
    createdAtMillis = createdAtMillis,
)

fun MentionEntity.toDomain(): Mention = Mention(
    id = id,
    entityId = entityId,
    signalId = signalId,
    conversationId = conversationId,
    surfaceText = surfaceText,
    startIndex = startIndex,
    endIndex = endIndex,
    confidence = confidence,
    createdAtMillis = createdAtMillis,
)

fun Relation.toEntity(): RelationEntity = RelationEntity(
    id = id,
    type = type.name,
    fromType = from.type.name,
    fromId = from.id,
    toType = to.type.name,
    toId = to.id,
    weight = weight,
    metadataJson = json.encodeToString(metadata),
    createdAtMillis = createdAtMillis,
)

fun RelationEntity.toDomain(): Relation = Relation(
    id = id,
    type = RelationType.valueOf(type),
    from = NodeRef(NodeType.valueOf(fromType), fromId),
    to = NodeRef(NodeType.valueOf(toType), toId),
    weight = weight,
    metadata = json.decodeFromString<Map<String, String>>(metadataJson),
    createdAtMillis = createdAtMillis,
)

// --- vector blob helpers (float list <-> little-endian byte array) ---

private fun List<Float>.toFloatBytes(): ByteArray {
    val buffer = ByteBuffer.allocate(size * Float.SIZE_BYTES)
    forEach { buffer.putFloat(it) }
    return buffer.array()
}

private fun ByteArray.toFloatList(): List<Float> {
    val buffer = ByteBuffer.wrap(this)
    return List(size / Float.SIZE_BYTES) { buffer.float }
}
