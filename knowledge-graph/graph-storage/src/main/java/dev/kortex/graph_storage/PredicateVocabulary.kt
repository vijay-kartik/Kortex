package dev.kortex.graph_storage

import dev.kortex.graph_core.AssertionPredicate
import dev.kortex.graph_storage.business.PredicateVocabularyEntity
import dev.kortex.graph_storage.business.PredicateVocabularyEntity_
import io.objectbox.BoxStore
import io.objectbox.query.QueryBuilder.StringOrder

/**
 * Learned half of the predicate vocabulary.
 *
 * [AssertionPredicate.canonicalize] stays a pure function over a fixed enum —
 * `graph-core` has no storage dependency and keeping it that way matters. This
 * class wraps it with the part that needs a database: counting the relation
 * phrases the fixed list doesn't cover, and applying promotions recorded against
 * them.
 *
 * Resolution order for a phrase:
 * 1. the fixed enum (exact name, then hardcoded direction-safe aliases);
 * 2. a promotion recorded in [PredicateVocabularyEntity.promotedToId];
 * 3. [AssertionPredicate.OTHER] — with the phrase itself preserved by the caller
 *    on the assertion, so nothing is lost while it waits to be promoted.
 */
class PredicateVocabulary(
    boxStore: BoxStore,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val box = boxStore.boxFor(PredicateVocabularyEntity::class.java)

    /**
     * The outcome of resolving one phrase.
     *
     * [normalized] is what the caller should persist as the assertion's raw
     * predicate — the canonical spelling, so two phrasings of the same relation
     * dedup against each other.
     */
    data class Resolution(
        val predicate: AssertionPredicate,
        val normalized: String,
        /** True when a recorded promotion (not the fixed enum) supplied the predicate. */
        val fromLearnedAlias: Boolean,
    )

    /**
     * Resolves [raw] and, when the fixed vocabulary doesn't recognize it, records
     * the sighting. Counting even phrases that already have a promotion is
     * deliberate: usage volume is what justifies promoting one into the enum.
     */
    @Synchronized
    fun resolveAndRecord(raw: String): Resolution {
        val normalized = AssertionPredicate.normalize(raw)
        val fixed = AssertionPredicate.canonicalize(raw)
        if (fixed != AssertionPredicate.OTHER) {
            return Resolution(fixed, normalized, fromLearnedAlias = false)
        }
        if (normalized.isEmpty()) {
            return Resolution(AssertionPredicate.OTHER, "", fromLearnedAlias = false)
        }

        val timestamp = now()
        val existing = find(normalized)
        val entity = existing ?: PredicateVocabularyEntity(
            phrase = normalized,
            sample = raw.trim(),
            firstSeenMillis = timestamp,
        )
        entity.occurrences += 1
        entity.lastSeenMillis = timestamp
        box.put(entity)

        val promoted = AssertionPredicate.fromId(entity.promotedToId)
            ?.takeIf { it != AssertionPredicate.OTHER }
        return Resolution(
            predicate = promoted ?: AssertionPredicate.OTHER,
            normalized = normalized,
            fromLearnedAlias = promoted != null,
        )
    }

    /** Resolves without recording — for read paths that must not mutate counts. */
    fun resolve(raw: String): Resolution {
        val normalized = AssertionPredicate.normalize(raw)
        val fixed = AssertionPredicate.canonicalize(raw)
        if (fixed != AssertionPredicate.OTHER) {
            return Resolution(fixed, normalized, fromLearnedAlias = false)
        }
        val promoted = find(normalized)?.let { AssertionPredicate.fromId(it.promotedToId) }
            ?.takeIf { it != AssertionPredicate.OTHER }
        return Resolution(promoted ?: AssertionPredicate.OTHER, normalized, promoted != null)
    }

    /**
     * Maps [raw] onto [predicate] from now on. This is the promotion path that
     * needs no code change: the next extraction using the phrase resolves to a
     * real predicate immediately.
     */
    @Synchronized
    fun promote(raw: String, predicate: AssertionPredicate) {
        val normalized = AssertionPredicate.normalize(raw)
        if (normalized.isEmpty()) return
        val timestamp = now()
        val entity = find(normalized) ?: PredicateVocabularyEntity(
            phrase = normalized,
            sample = raw.trim(),
            firstSeenMillis = timestamp,
            lastSeenMillis = timestamp,
        )
        entity.promotedToId = predicate.id
        box.put(entity)
    }

    /** Clears a promotion, sending the phrase back to OTHER. */
    @Synchronized
    fun demote(raw: String) {
        val entity = find(AssertionPredicate.normalize(raw)) ?: return
        entity.promotedToId = PredicateVocabularyEntity.NOT_PROMOTED
        box.put(entity)
    }

    /**
     * Un-promoted phrases, most frequent first — the promotion queue. This is
     * the evidence for which predicates the enum is actually missing.
     */
    fun pendingPromotion(limit: Int = 50): List<PredicateVocabularyEntity> =
        box.query()
            .equal(PredicateVocabularyEntity_.promotedToId, PredicateVocabularyEntity.NOT_PROMOTED.toLong())
            .orderDesc(PredicateVocabularyEntity_.occurrences)
            .build()
            .find(0, limit.toLong())

    /** Every recorded phrase, most frequent first. */
    fun all(limit: Int = 200): List<PredicateVocabularyEntity> =
        box.query()
            .orderDesc(PredicateVocabularyEntity_.occurrences)
            .build()
            .find(0, limit.toLong())

    private fun find(normalized: String): PredicateVocabularyEntity? =
        box.query()
            .equal(PredicateVocabularyEntity_.phrase, normalized, StringOrder.CASE_SENSITIVE)
            .build()
            .findFirst()
}
