package dev.kortex.graph_core

/**
 * Coarse grouping of node types. Gives "all event nodes" semantics without
 * a fake supertype enum value (enums cannot express hierarchy).
 */
enum class NodeCategory {
    IDENTITY,
    EVENT,
    KNOWLEDGE,
    ASSET,
}

/**
 * The node ontology: every kind of thing that can exist in the graph.
 *
 * ## ID contract (permanent)
 * - IDs are stable and are what storage persists. NEVER use [ordinal].
 * - IDs are additive-only: never renumbered, never reused, even after
 *   deprecation. Deprecated values stay in the enum, annotated @Deprecated.
 * - Ranges are gapped by category so new types slot in without renumbering:
 *   Identity 1-9, Events 10-19, Knowledge 20-29, Assets 30-39.
 *
 * ## Activation
 * The full ontology is defined up front (definitions are free; only IDs are
 * contractual). Milestones activate subsets:
 * - M1 (Contacts/CallLog/SMS): PERSON, PHONE_NUMBER, ORGANIZATION, CALL,
 *   MESSAGE, CONVERSATION
 * - M2 (Calendar): MEETING, LOCATION
 * - M3 (extraction pipeline): TOPIC, TASK, ASSERTION and remaining knowledge types
 */
enum class NodeType(val id: Int, val category: NodeCategory) {

    // ----- Identity (1-9) -----
    PERSON(1, NodeCategory.IDENTITY),
    ORGANIZATION(2, NodeCategory.IDENTITY),
    LOCATION(3, NodeCategory.IDENTITY),

    /**
     * First-class node, not a Person property. Makes entity resolution
     * graph-native: an unresolved number is simply a PHONE_NUMBER node with
     * no incoming HAS_PHONE edge yet; shared/reassigned numbers work naturally.
     */
    PHONE_NUMBER(4, NodeCategory.IDENTITY),

    /** First-class for the same entity-resolution reasons as [PHONE_NUMBER]. */
    EMAIL_ADDRESS(5, NodeCategory.IDENTITY),

    // ----- Events (10-19) -----
    MESSAGE(10, NodeCategory.EVENT),
    CALL(11, NodeCategory.EVENT),
    EMAIL(12, NodeCategory.EVENT),
    MEETING(13, NodeCategory.EVENT),

    /**
     * Thread node (WhatsApp chat, SMS thread, email thread). Carries
     * thread-level summaries and embeddings; messages link via PART_OF.
     */
    CONVERSATION(14, NodeCategory.EVENT),

    // ----- Knowledge (20-29) -----
    TOPIC(20, NodeCategory.KNOWLEDGE),
    TASK(21, NodeCategory.KNOWLEDGE),
    PROJECT(22, NodeCategory.KNOWLEDGE),
    DECISION(23, NodeCategory.KNOWLEDGE),
    COMMITMENT(24, NodeCategory.KNOWLEDGE),

    /** Synthesized episode aggregating many events ("Planning the Goa trip"). */
    MEMORY(25, NodeCategory.KNOWLEDGE),

    /**
     * Reified derived fact. Carries predicate, validity interval, confidence,
     * and provenance for anything extracted or inferred (never for
     * deterministic system-of-record facts, which are direct edges).
     * Connected via SUBJECT / OBJECT / DERIVED_FROM edges.
     */
    ASSERTION(26, NodeCategory.KNOWLEDGE),

    // ----- Assets (30-39) -----
    DOCUMENT(30, NodeCategory.ASSET),
    IMAGE(31, NodeCategory.ASSET),
    AUDIO(32, NodeCategory.ASSET),
    ;

    companion object {
        private val byId: Map<Int, NodeType> = buildMap {
            for (type in NodeType.entries) {
                val previous = put(type.id, type)
                check(previous == null) {
                    "Duplicate NodeType id ${type.id}: $previous and $type"
                }
            }
        }

        /**
         * Resolves a persisted id. Returns null for unknown ids so the read
         * path can skip-and-log instead of crashing on data written by a
         * newer app version.
         */
        fun fromId(id: Int): NodeType? = byId[id]
    }
}
