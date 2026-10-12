package dev.kortex.app.data.graph

import dev.kortex.app.domain.graph.GraphSnapshot
import dev.kortex.app.domain.graph.GraphSnapshotEdge
import dev.kortex.app.domain.graph.GraphSnapshotNode
import dev.kortex.app.domain.graph.GraphSnapshotSource
import dev.kortex.graph_core.EdgeType
import dev.kortex.graph_storage.GraphBuilder
import dev.kortex.graph_storage.GraphRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** [GraphSnapshotSource] over the on-device ObjectBox graph, labelling nodes the way the agent reads them. */
class StorageGraphSnapshotSource(
    private val repository: GraphRepository,
    private val builder: GraphBuilder,
) : GraphSnapshotSource {

    override suspend fun snapshot(): GraphSnapshot = withContext(Dispatchers.IO) {
        val nodes = repository.getAllNodes().map { handle ->
            GraphSnapshotNode(
                graphKey = handle.graphKey,
                // A node whose business row is missing still gets drawn, under its type.
                label = builder.nodeLabel(handle.graphKey) ?: handle.nodeType.name,
                type = handle.nodeType,
            )
        }
        val edges = repository.getAllEdges().mapNotNull { e ->
            val type = EdgeType.fromId(e.relationshipId) ?: return@mapNotNull null
            GraphSnapshotEdge(sourceKey = e.sourceKey, targetKey = e.targetKey, type = type)
        }
        GraphSnapshot(nodes, edges)
    }

    override suspend fun details(graphKey: Long): Map<String, String> =
        withContext(Dispatchers.IO) { builder.getNodeDetails(graphKey) }

    override suspend fun clear() = withContext(Dispatchers.IO) { builder.clear() }
}
