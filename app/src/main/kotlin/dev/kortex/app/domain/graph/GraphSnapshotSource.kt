package dev.kortex.app.domain.graph

import dev.kortex.graph_core.EdgeType
import dev.kortex.graph_core.NodeType

/**
 * The knowledge graph as the Graph tab shows it: the whole graph at once, one node's details,
 * and wiping it. Nodes are identified by their graph key.
 */
interface GraphSnapshotSource {
    suspend fun snapshot(): GraphSnapshot

    /** The node's stored fields, keyed by display name; empty when the node is gone. */
    suspend fun details(graphKey: Long): Map<String, String>

    /** Deletes every node, edge and embedding. */
    suspend fun clear()
}

data class GraphSnapshot(
    val nodes: List<GraphSnapshotNode>,
    val edges: List<GraphSnapshotEdge>,
)

data class GraphSnapshotNode(
    val graphKey: Long,
    val label: String,
    val type: NodeType,
)

data class GraphSnapshotEdge(
    val sourceKey: Long,
    val targetKey: Long,
    val type: EdgeType,
)
