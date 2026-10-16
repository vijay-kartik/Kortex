package dev.kortex.graph_storage

/**
 * Resolves a relation phrase to a predicate, recording phrases the fixed
 * vocabulary doesn't know. [PredicateVocabulary] is the real implementation.
 */
interface PredicateResolver {
    fun resolveAndRecord(raw: String): PredicateVocabulary.Resolution
}
