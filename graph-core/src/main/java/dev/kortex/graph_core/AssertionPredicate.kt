package dev.kortex.graph_core

/**
 * Predicate vocabulary for ASSERTION nodes — what a derived fact states
 * about its SUBJECT relative to its OBJECT.
 *
 * The provenance rule (locked):
 * - Deterministic facts from a system of record (Contacts, CallLog) →
 *   direct edges (HAS_PHONE, WORKS_AT, ...).
 * - Extracted or inferred facts (LLM/NER output) → ASSERTION nodes carrying
 *   one of these predicates plus validity interval, confidence, and
 *   DERIVED_FROM provenance.
 *
 * ## ID contract
 * Same as NodeType/EdgeType: stable, additive-only, never renumbered.
 *
 * ## Long-tail predicates
 * The extraction pipeline canonicalizes free-form relations to this
 * vocabulary (embedding-similarity matching, as in the extraction design).
 * Relations that don't canonicalize map to [OTHER] with the raw phrase kept
 * on the assertion node's own fields; recurring OTHER phrases are the signal
 * to promote a new predicate here, additively.
 */
enum class AssertionPredicate(val id: Int) {

    /** Escape hatch for un-canonicalized long-tail relations. */
    OTHER(0),

    // ----- Person ↔ Organization / Location (1-19) -----
    WORKS_AT(1),
    STUDIED_AT(2),
    LIVES_IN(3),
    TRAVELING_TO(4),
    VISITED(5),

    // ----- Person ↔ Person (20-39) -----
    FAMILY_OF(20),
    COLLEAGUE_OF(21),
    FRIEND_OF(22),
    MANAGER_OF(23),
    KNOWS(24),

    // ----- Person ↔ Topic / Project / Asset (40-59) -----
    INTERESTED_IN(40),
    WORKING_ON(41),
    OWNS(42),
    PREFERS(43),

    // ----- Temporal personal facts (60-79) -----
    BIRTHDAY_ON(60),
    ANNIVERSARY_ON(61),
    ;

    companion object {
        private val byId: Map<Int, AssertionPredicate> = buildMap {
            for (predicate in AssertionPredicate.entries) {
                val previous = put(predicate.id, predicate)
                check(previous == null) {
                    "Duplicate AssertionPredicate id ${predicate.id}: $previous and $predicate"
                }
            }
        }

        /** Resolves a persisted id; null for unknown ids (skip-and-log on read). */
        fun fromId(id: Int): AssertionPredicate? = byId[id]
    }
}
