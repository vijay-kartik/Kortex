package dev.kortex.graph_core

/**
 * Public reference to a graph node, returned across the graph API boundary.
 *
 * Carries the stable external identity plus enough type information for the
 * caller to dispatch to the right repository for hydration — without another
 * registry lookup just to learn what kind of node it is.
 *
 * This is the type app/feature code sees. It never contains the internal
 * graphKey. Inside the engine, traversals operate on [NodeHandle] and are
 * batch-converted to GraphReference at the boundary (one indexed registry
 * query per batch).
 */
data class GraphReference(
    val graphId: GraphId,
    val nodeType: NodeType,
)

/**
 * INTERNAL traversal handle: the graph engine's working currency.
 *
 * Not for use by app/feature code. It is public only because the storage and
 * repository implementations live in a separate module; treat it as
 * graph-infrastructure API.
 *
 * Carrying the Long key means multi-hop traversals are zero-lookup: edges
 * store keys, so hops never round-trip through the registry. Resolution
 * happens exactly twice per query — GraphId → key on the way in, key →
 * GraphId on the way out.
 *
 * @param graphKey registry-assigned, graph-wide unique key. Required because
 *   ObjectBox @Id values are only unique per box, so raw entity IDs are
 *   ambiguous across node types.
 */
data class NodeHandle(
    val graphKey: Long,
    val nodeType: NodeType,
)
