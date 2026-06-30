package dev.kortex.core.ambient

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Everything the agent produces after analyzing new signals against stored context. Per the
 * product vision it either surfaces an actionable [ActionCard] or quietly persists a
 * [MemoryEntry] — usually some of each — and along the way it also emits graph artifacts
 * ([entities], [mentions], [relations]) to fold into the knowledge graph (Layer 2). The
 * caller persists all of these.
 */
@Serializable
data class AnalysisOutcome(
    val cards: List<ActionCard> = emptyList(),
    val memories: List<MemoryEntry> = emptyList(),
    val entities: List<Entity> = emptyList(),
    val mentions: List<Mention> = emptyList(),
    val relations: List<Relation> = emptyList(),
)

/**
 * A generative, action-bearing card shown to the user. Its [summary] is the combined
 * cross-medium digest; [actions] are the suggested things the user can do next.
 */
@Serializable
data class ActionCard(
    val id: String,
    val title: String,
    val summary: String,
    val contactId: String,
    val conversationId: String? = null,
    val actions: List<CardAction> = emptyList(),
    val priority: Priority = Priority.MEDIUM,
    /** Provenance: which signals (across mediums) this card was derived from. */
    val sourceSignalIds: List<String> = emptyList(),
    val createdAtMillis: Long,
    val expiresAtMillis: Long? = null,
    val status: CardStatus = CardStatus.NEW,
)

@Serializable
enum class Priority { LOW, MEDIUM, HIGH, URGENT }

@Serializable
enum class CardStatus { NEW, SHOWN, ACTED, DISMISSED, EXPIRED }

/**
 * The open-ended set of things a card can offer. A sealed hierarchy (serialized with a
 * "type" discriminator) so new action kinds are added without breaking existing data, and
 * so the execution layer can exhaustively handle each. Each carries enough payload for a
 * Tool to actually perform it later (pattern 5). [atMillis] fields enable "do this later"
 * actions like scheduled check-ins (the periodic/scheduled trigger the user wanted).
 */
@Serializable
sealed interface CardAction {
    val label: String

    @Serializable
    @SerialName("reply_text")
    data class ReplyText(
        override val label: String = "Reply",
        val suggestedText: String,
        /** Which medium to reply on; null = let the user/agent pick. */
        val viaAppId: String? = null,
    ) : CardAction

    @Serializable
    @SerialName("share_location")
    data class ShareLocation(
        override val label: String = "Share location",
        val live: Boolean = false,
    ) : CardAction

    @Serializable
    @SerialName("share_media")
    data class ShareMedia(
        override val label: String = "Share",
        val mediaType: AttachmentType,
        val hint: String? = null,
    ) : CardAction

    @Serializable
    @SerialName("set_reminder")
    data class SetReminder(
        override val label: String = "Remind me",
        val text: String,
        val atMillis: Long,
    ) : CardAction

    @Serializable
    @SerialName("schedule_checkin")
    data class ScheduleCheckIn(
        override val label: String = "Schedule check-in",
        val message: String,
        val atMillis: Long,
    ) : CardAction

    @Serializable
    @SerialName("create_event")
    data class CreateCalendarEvent(
        override val label: String = "Add to calendar",
        val title: String,
        val startMillis: Long,
        val endMillis: Long? = null,
        val location: String? = null,
    ) : CardAction

    @Serializable
    @SerialName("call")
    data class CallContact(override val label: String = "Call") : CardAction

    /** Extensibility escape hatch for actions we haven't modeled yet. */
    @Serializable
    @SerialName("custom")
    data class Custom(
        override val label: String,
        val actionId: String,
        val payload: Map<String, String> = emptyMap(),
    ) : CardAction
}

/**
 * A durable insight the agent chose to remember instead of (or in addition to) surfacing.
 * This is the long-term context that future analyses retrieve (pattern 8 Memory, 14 RAG).
 */
@Serializable
data class MemoryEntry(
    val id: String,
    val content: String,
    val kind: MemoryKind = MemoryKind.FACT,
    val contactId: String? = null,
    val sourceSignalIds: List<String> = emptyList(),
    /** 0..1 importance, for ranking/decay during retrieval. */
    val salience: Double = 0.5,
    val tags: List<String> = emptyList(),
    val createdAtMillis: Long,
    /** Vector-index layer (Layer 3 / RAG): embedding of [content], filled in when indexed. */
    val embedding: List<Float>? = null,
    val embeddingModel: String? = null,
)

@Serializable
enum class MemoryKind { FACT, PREFERENCE, EVENT, COMMITMENT, RELATIONSHIP, OTHER }
