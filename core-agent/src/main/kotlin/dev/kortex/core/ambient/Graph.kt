package dev.kortex.core.ambient

import kotlinx.serialization.Serializable

/**
 * The knowledge-graph layer (Layer 2). The temporal Signal log is the source of truth;
 * this graph is a projection over it: heterogeneous nodes (contacts, conversations,
 * signals, entities, memories, topics) connected by typed [Relation] edges. On Android
 * this maps to simple `nodes` + `edges` tables in Room (a property graph over SQLite) —
 * no graph database needed.
 *
 * [Entity] + [Mention] are the contact-annotation feature: when a message mentions someone
 * (or a place/date/commitment), we extract an Entity and record a Mention pointing at the
 * exact signal. Person entities resolve to a known [ContactRef] when possible, which is how
 * a name dropped in one thread links to that person's own conversations.
 */

/** A reference to any node in the graph, so edges can connect heterogeneous types. */
@Serializable
data class NodeRef(val type: NodeType, val id: String)

@Serializable
enum class NodeType { CONTACT, CONVERSATION, SIGNAL, ENTITY, MEMORY, TOPIC }

/** Something referenced in the user's world: a person, place, time, event, etc. */
@Serializable
data class Entity(
    val id: String,
    val type: EntityType,
    /** Canonical/display form as extracted, e.g. "Rahul", "Friday", "the dentist". */
    val name: String,
    /** Set when a PERSON entity is resolved to a known contact; null = provisional. */
    val resolvedContactId: String? = null,
    /** Normalized value where meaningful: ISO time for DATE_TIME, place id, etc. */
    val normalizedValue: String? = null,
    val aliases: List<String> = emptyList(),
    val createdAtMillis: Long,
) {
    /** A person we've heard about but haven't matched to a contact yet. */
    val isProvisional: Boolean get() = type == EntityType.PERSON && resolvedContactId == null
}

@Serializable
enum class EntityType { PERSON, PLACE, ORGANIZATION, DATE_TIME, EVENT, COMMITMENT, TOPIC, OTHER }

/** One occurrence of an [Entity] inside a specific signal's content. */
@Serializable
data class Mention(
    val id: String,
    val entityId: String,
    val signalId: String,
    val conversationId: String? = null,
    /** Exact text that triggered the mention, e.g. "Rahul". */
    val surfaceText: String,
    /** Character span in the signal content, if known. */
    val startIndex: Int? = null,
    val endIndex: Int? = null,
    val confidence: Double = 1.0,
    val createdAtMillis: Long,
)

/**
 * A typed, directed edge between two nodes. Examples:
 *   CONTACT     --PARTICIPATES_IN--> CONVERSATION
 *   SIGNAL      --BELONGS_TO-------> CONVERSATION
 *   CONVERSATION--MENTIONS---------> ENTITY
 *   MEMORY      --ABOUT------------> CONTACT
 *   SIGNAL      --FOLLOWS----------> SIGNAL          (temporal chain)
 *   CARD/MEMORY --DERIVED_FROM-----> SIGNAL          (provenance)
 */
@Serializable
data class Relation(
    val id: String,
    val type: RelationType,
    val from: NodeRef,
    val to: NodeRef,
    /** Optional strength/relevance for ranking and decay. */
    val weight: Double? = null,
    val metadata: Map<String, String> = emptyMap(),
    val createdAtMillis: Long,
)

@Serializable
enum class RelationType { PARTICIPATES_IN, BELONGS_TO, ABOUT, MENTIONS, FOLLOWS, DERIVED_FROM, RELATES_TO }
