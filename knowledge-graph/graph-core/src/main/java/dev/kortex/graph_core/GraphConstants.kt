package dev.kortex.graph_core

/**
 * Shared limits referenced by traversal and query implementations.
 * Kept here so later layers never invent magic numbers.
 */
object GraphConstants {

    /** Hard ceiling on traversal depth; guards against runaway walks. */
    const val MAX_TRAVERSAL_DEPTH: Int = 6

    /** Default page size for neighbor queries. */
    const val DEFAULT_NEIGHBOR_LIMIT: Int = 100

    /** Default result count for vector-similarity queries. */
    const val DEFAULT_VECTOR_LIMIT: Int = 20
}
