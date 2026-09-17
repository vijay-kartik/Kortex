package dev.kortex.graph_core

/**
 * Public API model for one edge: what traversal queries return.
 *
 * Preserves graph semantics (who, whom, how related) without leaking
 * storage types (EdgeEntity) or dropping the relationship the way a bare
 * List<GraphReference> would.
 *
 * Pure domain: no storage IDs, no ObjectBox.
 */
data class Relationship(
    val source: GraphReference,
    val target: GraphReference,
    val type: EdgeType,
)
