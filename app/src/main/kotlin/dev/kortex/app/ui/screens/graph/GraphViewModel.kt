package dev.kortex.app.ui.screens.graph

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.kortex.app.domain.graph.GraphSnapshotSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.random.Random

@HiltViewModel
class GraphViewModel @Inject constructor(
    private val source: GraphSnapshotSource,
) : ViewModel() {

    private val _nodes = MutableStateFlow<List<GraphUiNode>>(emptyList())
    val nodes: StateFlow<List<GraphUiNode>> = _nodes.asStateFlow()

    private val _edges = MutableStateFlow<List<GraphUiEdge>>(emptyList())
    val edges: StateFlow<List<GraphUiEdge>> = _edges.asStateFlow()

    /** False until the first [loadGraph] finishes, so the screen can tell "still loading" from "empty graph". */
    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    private val _selectedNodeDetails = MutableStateFlow<NodeDetails?>(null)
    val selectedNodeDetails: StateFlow<NodeDetails?> = _selectedNodeDetails.asStateFlow()

    fun selectNode(nodeId: Long?) {
        if (nodeId == null) {
            _selectedNodeDetails.value = null
            return
        }
        viewModelScope.launch {
            val node = _nodes.value.find { it.id == nodeId } ?: return@launch
            val connectedEdges = _edges.value.filter { it.sourceId == nodeId || it.targetId == nodeId }
            _selectedNodeDetails.value = NodeDetails(node, connectedEdges, source.details(nodeId))
        }
    }

    fun loadGraph() {
        viewModelScope.launch { reload() }
    }

    fun resetGraph() {
        viewModelScope.launch {
            source.clear()
            reload()
        }
    }

    private suspend fun reload() {
        val snapshot = source.snapshot()
        _nodes.value = snapshot.nodes.map { n ->
            GraphUiNode(
                id = n.graphKey,
                label = n.label,
                type = n.type,
                x = Random.nextFloat() * 1000f,
                y = Random.nextFloat() * 1000f
            )
        }
        _edges.value = snapshot.edges.map { e ->
            GraphUiEdge(sourceId = e.sourceKey, targetId = e.targetKey, type = e.type)
        }
        _loaded.value = true
    }
}
