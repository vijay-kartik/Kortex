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

    /**
     * Whether this predicate's OBJECT is another person (a person↔person
     * relation). Lets callers type the object node as PERSON instead of
     * defaulting it to TOPIC — e.g. the object of MANAGER_OF is a person, not a
     * topic. Storage only has PERSON and TOPIC entities today, so this is the
     * binary that matters.
     */
    val objectIsPerson: Boolean get() = this in PERSON_TO_PERSON

    companion object {
        /** Predicates whose OBJECT is another person (the "Person ↔ Person" group). */
        private val PERSON_TO_PERSON: Set<AssertionPredicate> =
            setOf(FAMILY_OF, COLLEAGUE_OF, FRIEND_OF, MANAGER_OF, KNOWS)

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

        /**
         * Direction-preserving synonyms → canonical predicate. Only mappings that
         * keep the SUBJECT→OBJECT direction belong here: inverse phrasings (e.g.
         * "reports to", which flips who manages whom) are deliberately absent so
         * they fall through to [OTHER] rather than silently asserting the reverse.
         * Keys are already normalized (uppercase, separators → '_').
         */
        private val ALIASES: Map<String, AssertionPredicate> = mapOf(
            "MANAGER" to MANAGER_OF,
            "MANAGES" to MANAGER_OF,
            "WORKS_FOR" to WORKS_AT,
            "EMPLOYED_AT" to WORKS_AT,
            "EMPLOYED_BY" to WORKS_AT,
            "STUDIES" to STUDIED_AT,
            "STUDIES_AT" to STUDIED_AT,
            "LIVES" to LIVES_IN,
            "RESIDES_IN" to LIVES_IN,
            "COLLEAGUE" to COLLEAGUE_OF,
            "COWORKER" to COLLEAGUE_OF,
            "COWORKER_OF" to COLLEAGUE_OF,
            "FRIEND" to FRIEND_OF,
            "FRIENDS_WITH" to FRIEND_OF,
            "FAMILY" to FAMILY_OF,
            "RELATIVE_OF" to FAMILY_OF,
            "INTEREST" to INTERESTED_IN,
            "INTERESTED" to INTERESTED_IN,
            "WORKING" to WORKING_ON,
            "OWNER_OF" to OWNS,
        )

        /**
         * Canonicalizes a free-form relation phrase to a predicate. Handles case
         * and separator differences (spaces / hyphens → underscores), then an exact
         * enum-name match, then a small set of direction-safe synonyms; anything
         * unrecognized maps to [OTHER].
         *
         * Replaces a raw `valueOf`, which matched only the exact enum name and so
         * collapsed almost every real phrasing ("manager", "works for") to OTHER.
         */
        fun canonicalize(raw: String): AssertionPredicate {
            val normalized = raw.trim().uppercase().replace(Regex("[\\s-]+"), "_")
            if (normalized.isEmpty()) return OTHER
            entries.firstOrNull { it.name == normalized }?.let { return it }
            return ALIASES[normalized] ?: OTHER
        }
    }
}
