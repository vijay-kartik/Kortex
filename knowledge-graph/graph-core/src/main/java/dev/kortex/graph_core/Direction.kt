package dev.kortex.graph_core

/**
 * Traversal direction relative to a node. The graph itself is strictly
 * directed; direction is a query-time concept only.
 */
enum class Direction {
    OUTGOING,
    INCOMING,
    BOTH,
}
