package dev.kortex.graph_storage.business

import io.objectbox.annotation.Entity
import io.objectbox.annotation.Id
import io.objectbox.annotation.Index
import io.objectbox.annotation.Unique

/**
 * One long-tail relation phrase the extractor produced that the fixed
 * [dev.kortex.graph_core.AssertionPredicate] vocabulary does not cover, with how
 * often it has been seen.
 *
 * The predicate enum's own doc says recurring OTHER phrases are the signal to
 * promote a new predicate additively — but nothing recorded them, so there was
 * never any evidence to act on. This table is that evidence, and doubles as a
 * runtime alias map: filling in [promotedToId] makes every future occurrence of
 * the phrase resolve to a real predicate without an enum change or a rebuild.
 */
@Entity
class PredicateVocabularyEntity(
    @Id var id: Long = 0,

    /** Normalized via `AssertionPredicate.normalize` so casing/spacing variants share a row. */
    @Unique
    @Index
    var phrase: String = "",

    /** An example of the phrase as originally written, for display. */
    var sample: String = "",

    var occurrences: Long = 0,

    var firstSeenMillis: Long = 0,

    var lastSeenMillis: Long = 0,

    /**
     * Id of the [dev.kortex.graph_core.AssertionPredicate] this phrase has been
     * promoted to, or [NOT_PROMOTED]. Stored as the raw id rather than the enum
     * so an id written by a newer build round-trips through an older one.
     */
    var promotedToId: Int = NOT_PROMOTED,
) {
    companion object {
        const val NOT_PROMOTED = -1
    }
}
