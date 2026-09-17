package dev.kortex.graph_core

/**
 * The edge ontology. Every value documents its direction convention
 * (source → target), which [GraphSchema] enforces at build time.
 *
 * ## ID contract (permanent)
 * Same rules as [NodeType]: stable, additive-only, never renumbered or
 * reused, never use ordinal. Ranges: Participation 1-9, Knowledge 10-19,
 * Assertion 20-29, Identity 30-39, Structure/Time 40-49.
 *
 * ## Design rules
 * - Edges are structural only: no properties, no timestamps, no confidence.
 *   Anything richer belongs on a node (event nodes own their time;
 *   ASSERTION nodes own provenance/validity/confidence).
 * - No redundant inverses. There is no PREVIOUS: the inverse of NEXT is
 *   traversing NEXT with Direction.INCOMING. Storing both directions doubles
 *   edge count and creates an inconsistency class.
 */
enum class EdgeType(val id: Int) {

    // ----- Participation (1-9): PERSON → event -----
    /** PERSON → MESSAGE | CALL | EMAIL | MEETING | CONVERSATION. */
    PARTICIPATED_IN(1),

    /** PERSON → MESSAGE | EMAIL | CALL (initiator / caller). */
    SENT(2),

    /** PERSON → MESSAGE | EMAIL | CALL (recipient / callee). */
    RECEIVED(3),

    // ----- Knowledge (10-19) -----
    /** event → TOPIC. */
    MENTIONED(10),

    /** event | TOPIC → PROJECT. */
    ABOUT(11),

    /** TOPIC → TOPIC. */
    RELATED_TO(12),

    /** event → TASK | DECISION | COMMITMENT. */
    CREATED(13),

    /** MEMORY → event | TOPIC | TASK | DECISION | COMMITMENT. */
    AGGREGATES(14),

    // ----- Assertion (20-29): reified-fact plumbing -----
    /** ASSERTION → the node the fact is about. */
    SUBJECT(20),

    /** ASSERTION → the node that is the fact's value. */
    OBJECT(21),

    /** ASSERTION → source event it was extracted from. */
    DERIVED_FROM(22),

    // ----- Identity (30-39): deterministic, from system-of-record sync -----
    /** PERSON → PHONE_NUMBER. */
    HAS_PHONE(30),

    /** PERSON → EMAIL_ADDRESS. */
    HAS_EMAIL(31),

    /** PERSON → ORGANIZATION. Contacts-sourced only; extracted employment is an ASSERTION. */
    WORKS_AT(32),

    /** PERSON → LOCATION. Contacts-sourced only; extracted residence is an ASSERTION. */
    LIVES_AT(33),

    // ----- Structure / time (40-49) -----
    /** event → event, chronological successor within the same stream. */
    NEXT(40),

    /** MESSAGE → MESSAGE, EMAIL → EMAIL. */
    REPLIES_TO(41),

    /** MESSAGE → CONVERSATION. */
    PART_OF(42),

    /** DOCUMENT | IMAGE | AUDIO → MESSAGE | EMAIL | MEETING. */
    ATTACHED_TO(43),
    ;

    companion object {
        private val byId: Map<Int, EdgeType> = buildMap {
            for (type in EdgeType.entries) {
                val previous = put(type.id, type)
                check(previous == null) {
                    "Duplicate EdgeType id ${type.id}: $previous and $type"
                }
            }
        }

        /** Resolves a persisted id; null for unknown ids (skip-and-log on read). */
        fun fromId(id: Int): EdgeType? = byId[id]
    }
}
