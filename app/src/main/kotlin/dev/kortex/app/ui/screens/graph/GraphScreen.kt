package dev.kortex.app.ui.screens.graph

import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.res.painterResource
import dev.kortex.design.Alarm
import dev.kortex.design.Amber
import dev.kortex.design.Edge
import dev.kortex.design.Ink
import dev.kortex.design.InkSoft
import dev.kortex.design.Muted
import dev.kortex.design.Panel
import dev.kortex.design.R
import dev.kortex.design.Synapse
import dev.kortex.design.Teal
import dev.kortex.design.Void
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.objectbox.BoxStore
import javax.inject.Inject
import dev.kortex.graph_core.EdgeType
import dev.kortex.graph_core.NodeCategory
import dev.kortex.graph_core.NodeType
import dev.kortex.graph_storage.EdgeEntity
import dev.kortex.graph_storage.EmbeddingEntity
import dev.kortex.graph_storage.GraphRegistryEntity
import dev.kortex.graph_storage.business.AssertionEntity
import dev.kortex.graph_storage.business.PersonEntity
import dev.kortex.graph_storage.business.TopicEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.sqrt
import kotlin.random.Random

data class GraphUiNode(
    val id: Long,
    val label: String,
    val type: NodeType,
    var x: Float = 0f,
    var y: Float = 0f,
    var vx: Float = 0f,
    var vy: Float = 0f
)

data class GraphUiEdge(
    val sourceId: Long,
    val targetId: Long,
    val type: EdgeType
)

data class NodeDetails(
    val node: GraphUiNode,
    val connectedEdges: List<GraphUiEdge>,
    val details: Map<String, String>
)

@HiltViewModel
class GraphViewModel @Inject constructor(
    private val boxStore: BoxStore,
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
        viewModelScope.launch(Dispatchers.IO) {
            val node = _nodes.value.find { it.id == nodeId } ?: return@launch
            val connectedEdges = _edges.value.filter { it.sourceId == nodeId || it.targetId == nodeId }
            
            // graphKey is the registry's @Id, so this is a direct lookup.
            val reg = boxStore.boxFor(GraphRegistryEntity::class.java).get(nodeId)
            
            val detailsMap = mutableMapOf<String, String>()
            if (reg != null) {
                when (node.type) {
                    NodeType.PERSON -> {
                        val p = boxStore.boxFor(PersonEntity::class.java).get(reg.businessEntityId)
                        if (p != null) {
                            detailsMap["Name"] = p.name
                            if (p.notes.isNotBlank()) detailsMap["Notes"] = p.notes
                        }
                    }
                    NodeType.TOPIC -> {
                        val t = boxStore.boxFor(TopicEntity::class.java).get(reg.businessEntityId)
                        if (t != null) {
                            detailsMap["Label"] = t.label
                            if (t.description.isNotBlank()) detailsMap["Description"] = t.description
                        }
                    }
                    NodeType.ASSERTION -> {
                        val a = boxStore.boxFor(AssertionEntity::class.java).get(reg.businessEntityId)
                        if (a != null) {
                            detailsMap["Predicate"] = a.displayPredicate
                            detailsMap["Confidence"] = "%.2f".format(a.confidence)
                        }
                    }
                    else -> {}
                }
            }
            
            _selectedNodeDetails.value = NodeDetails(node, connectedEdges, detailsMap)
        }
    }

    fun loadGraph() {
        viewModelScope.launch(Dispatchers.IO) {
            val registryBox = boxStore.boxFor(GraphRegistryEntity::class.java)
            val edgeBox = boxStore.boxFor(EdgeEntity::class.java)
            val personBox = boxStore.boxFor(PersonEntity::class.java)
            val topicBox = boxStore.boxFor(TopicEntity::class.java)
            val assertionBox = boxStore.boxFor(AssertionEntity::class.java)

            val registryNodes = registryBox.all
            val rawEdges = edgeBox.all

            val uiNodes = registryNodes.mapNotNull { reg ->
                val type = NodeType.fromId(reg.nodeTypeId) ?: return@mapNotNull null
                val label = when (type) {
                    NodeType.PERSON -> personBox.get(reg.businessEntityId)?.name ?: "Person"
                    NodeType.TOPIC -> topicBox.get(reg.businessEntityId)?.label ?: "Topic"
                    NodeType.ASSERTION -> {
                        val ast = assertionBox.get(reg.businessEntityId)
                        // Prefer the extractor's own phrase for long-tail relations:
                        // the predicate enum reports every one of them as OTHER, which
                        // renders a graph full of identical unreadable nodes.
                        ast?.rawPredicate?.takeIf { it.isNotBlank() }
                            ?: ast?.predicate?.name
                            ?: "Assertion"
                    }
                    else -> type.name
                }
                
                GraphUiNode(
                    id = reg.graphKey,
                    label = label,
                    type = type,
                    x = Random.nextFloat() * 1000f,
                    y = Random.nextFloat() * 1000f
                )
            }

            val uiEdges = rawEdges.mapNotNull { e ->
                val type = EdgeType.fromId(e.relationshipId.toInt()) ?: return@mapNotNull null
                GraphUiEdge(sourceId = e.sourceKey, targetId = e.targetKey, type = type)
            }

            _nodes.value = uiNodes
            _edges.value = uiEdges
            _loaded.value = true
        }
    }

    fun resetGraph() {
        viewModelScope.launch(Dispatchers.IO) {
            boxStore.runInTx {
                boxStore.boxFor(GraphRegistryEntity::class.java).removeAll()
                boxStore.boxFor(EdgeEntity::class.java).removeAll()
                boxStore.boxFor(EmbeddingEntity::class.java).removeAll()
                boxStore.boxFor(PersonEntity::class.java).removeAll()
                boxStore.boxFor(TopicEntity::class.java).removeAll()
                boxStore.boxFor(AssertionEntity::class.java).removeAll()
            }
            loadGraph()
        }
    }
}

@Composable
fun GraphScreen(vm: GraphViewModel = hiltViewModel()) {
    val nodes by vm.nodes.collectAsState()
    val edges by vm.edges.collectAsState()
    val loaded by vm.loaded.collectAsState()
    val selectedNodeDetails by vm.selectedNodeDetails.collectAsState()
    var showResetDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        vm.loadGraph()
    }

    Box(modifier = Modifier.fillMaxSize().background(Void)) {
        if (nodes.isEmpty()) {
            // Blank until the first load finishes, rather than flashing "Graph is empty."
            if (loaded) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text("Graph is empty.", color = Muted)
                }
            }
        } else {
            ForceDirectedGraphCanvas(nodes, edges) { nodeId ->
                vm.selectNode(nodeId)
            }
        }

        // HUD Overlay
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(24.dp)
                .background(Panel.copy(alpha = 0.8f), shape = RoundedCornerShape(8.dp))
                .border(1.dp, Edge, RoundedCornerShape(8.dp))
                .padding(16.dp)
        ) {
            Text("KORTEX SEMANTIC NETWORK", style = MaterialTheme.typography.labelMedium.copy(color = Amber, letterSpacing = 1.sp))
            Text("NODES: ${nodes.size} // EDGES: ${edges.size}", style = MaterialTheme.typography.labelSmall.copy(color = Muted, letterSpacing = 1.sp), modifier = Modifier.padding(top = 4.dp))
        }

        selectedNodeDetails?.let { details ->
            Column(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(24.dp)
                    .background(Panel.copy(alpha = 0.95f), shape = RoundedCornerShape(8.dp))
                    .border(1.dp, Edge, RoundedCornerShape(8.dp))
                    .padding(16.dp)
            ) {
                Text(details.node.type.name, style = MaterialTheme.typography.labelSmall.copy(color = Amber, letterSpacing = 1.sp))
                Text(details.node.label, style = MaterialTheme.typography.titleMedium.copy(color = Ink), modifier = Modifier.padding(top = 4.dp, bottom = 8.dp))

                details.details.forEach { (k, v) ->
                    Text("$k:", style = MaterialTheme.typography.labelSmall.copy(color = Muted))
                    Text(v, style = MaterialTheme.typography.bodySmall.copy(color = InkSoft), modifier = Modifier.padding(bottom = 6.dp))
                }
            }
        }

        // Floating Settings/Reset Button
        IconButton(
            onClick = { showResetDialog = true },
            modifier = Modifier.align(Alignment.TopEnd).padding(16.dp)
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_tune),
                contentDescription = "Memory Settings",
                tint = Muted
            )
        }
    }

    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text("Reset Knowledge Graph") },
            text = { Text("Are you sure you want to permanently delete all memory nodes and edges? This cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.resetGraph()
                        showResetDialog = false
                    }
                ) {
                    Text("Reset", color = Alarm)
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun ForceDirectedGraphCanvas(nodes: List<GraphUiNode>, edges: List<GraphUiEdge>, onNodeSelected: (Long?) -> Unit) {
    val coroutineScope = rememberCoroutineScope()
    var canvasWidth by remember { mutableStateOf(0f) }
    var canvasHeight by remember { mutableStateOf(0f) }
    val textMeasurer = rememberTextMeasurer()
    // Read outside the Canvas: its draw lambda isn't composable, so it can't reach the theme.
    val labelStyle = MaterialTheme.typography.labelMedium.copy(color = InkSoft, fontSize = 11.sp)
    // Labels don't change while the layout moves, so lay them out once per graph rather than every frame.
    val labelLayouts = remember(nodes, labelStyle, textMeasurer) {
        nodes.map { textMeasurer.measure(text = it.label, style = labelStyle) }
    }

    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var draggedNodeId by remember { mutableStateOf<Long?>(null) }

    val nodeIndex = remember(nodes) { nodes.withIndex().associate { (i, node) -> node.id to i } }
    val resolvedEdges = remember(nodeIndex, edges) { resolveEdges(nodeIndex, edges) }
    // Node positions are plain vars, so the Canvas reads this to redraw when the layout or a drag moves them.
    var layoutFrame by remember { mutableIntStateOf(0) }
    // Bumped when a drag starts or ends, to wake a layout that has already settled.
    var layoutWake by remember { mutableIntStateOf(0) }

    // Setup positions if not initialized
    LaunchedEffect(nodes, canvasWidth, canvasHeight) {
        if (canvasWidth > 0 && canvasHeight > 0) {
            nodes.forEach {
                if (it.x == 0f && it.y == 0f) {
                    it.x = canvasWidth / 2f + (Random.nextFloat() - 0.5f) * 1000f
                    it.y = canvasHeight / 2f + (Random.nextFloat() - 0.5f) * 1000f
                }
            }
        }
    }

    // Force-directed layout physics loop. Each tick's math runs off the main thread on a
    // copy of the positions; the loop stops once the layout settles or after a bounded
    // number of ticks, and restarts when the graph changes or a drag starts or ends.
    LaunchedEffect(nodes, resolvedEdges, layoutWake) {
        if (nodes.isEmpty()) return@LaunchedEffect
        val state = LayoutState(nodes.size)
        repeat(MAX_LAYOUT_ITERATIONS) {
            nodes.forEachIndexed { i, node ->
                state.x[i] = node.x
                state.y[i] = node.y
                state.vx[i] = node.vx
                state.vy[i] = node.vy
            }
            val pinned = draggedNodeId?.let { nodeIndex[it] } ?: -1
            val cx = canvasWidth / 2f
            val cy = canvasHeight / 2f
            val energy = withContext(Dispatchers.Default) {
                stepForceLayout(state, resolvedEdges, pinned, cx, cy)
            }
            // Leave a dragged node where the finger put it, even if the drag began mid-tick.
            val dragged = draggedNodeId?.let { nodeIndex[it] } ?: -1
            nodes.forEachIndexed { i, node ->
                if (i != pinned && i != dragged) {
                    node.x = state.x[i]
                    node.y = state.y[i]
                    node.vx = state.vx[i]
                    node.vy = state.vy[i]
                }
            }
            layoutFrame++

            if (draggedNodeId == null && isLayoutSettled(energy, nodes.size)) return@LaunchedEffect
            delay(16) // ~60fps
        }
    }

    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .background(Void)
            .pointerInput(Unit) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    if (draggedNodeId == null) {
                        scale = (scale * zoom).coerceIn(0.1f, 5f)
                        offset += pan
                    }
                }
            }
            .pointerInput(nodes) {
                detectDragGestures(
                    onDragStart = { pointer ->
                        // Reverse transform pointer to graph space (accounting for center scale origin)
                        val cx = canvasWidth / 2f
                        val cy = canvasHeight / 2f
                        val graphX = (pointer.x - cx - offset.x) / scale + cx
                        val graphY = (pointer.y - cy - offset.y) / scale + cy
                        val clicked = nodes.find {
                            val dx = it.x - graphX
                            val dy = it.y - graphY
                            sqrt(dx * dx + dy * dy) < 40f
                        }
                        if (clicked != null) {
                            draggedNodeId = clicked.id
                            layoutWake++
                            onNodeSelected(clicked.id)
                        } else {
                            onNodeSelected(null)
                        }
                    },
                    onDragEnd = {
                        if (draggedNodeId != null) layoutWake++
                        draggedNodeId = null
                    },
                    onDragCancel = {
                        if (draggedNodeId != null) layoutWake++
                        draggedNodeId = null
                    }
                ) { change, dragAmount ->
                    change.consume()
                    draggedNodeId?.let { id ->
                        val node = nodeIndex[id]?.let { nodes[it] }
                        if (node != null) {
                            node.x += dragAmount.x / scale
                            node.y += dragAmount.y / scale
                            node.vx = 0f
                            node.vy = 0f
                            layoutFrame++
                        }
                    }
                }
            }
            .graphicsLayer(
                scaleX = scale,
                scaleY = scale,
                translationX = offset.x,
                translationY = offset.y
            )
    ) {
        canvasWidth = size.width
        canvasHeight = size.height
        layoutFrame // Read so the Canvas redraws whenever node positions change.

        // Draw edges (curved paths)
        for (e in 0 until resolvedEdges.size) {
            val source = nodes[resolvedEdges.source[e]]
            val target = nodes[resolvedEdges.target[e]]
            val isActiveEdge = draggedNodeId != null && (source.id == draggedNodeId || target.id == draggedNodeId)
            val edgeColor = if (isActiveEdge) Synapse.copy(alpha = 0.8f) else Edge.copy(alpha = 0.6f)

            val path = Path().apply {
                moveTo(source.x, source.y)
                // Bezier curve for organic feel
                val ctrlX = (source.x + target.x) / 2f + (target.y - source.y) * 0.2f
                val ctrlY = (source.y + target.y) / 2f + (source.x - target.x) * 0.2f
                quadraticBezierTo(ctrlX, ctrlY, target.x, target.y)
            }

            drawPath(
                path = path,
                color = edgeColor,
                style = Stroke(width = if (isActiveEdge) 3f else 1.5f)
            )
        }

        // Draw nodes
        nodes.forEachIndexed { i, node ->
            val nodeColor = node.type.category.color()

            val isActiveNode = node.id == draggedNodeId
            val radius = 20f

            // Ambient halo (static, so a settled graph doesn't redraw every frame)
            drawCircle(
                color = nodeColor.copy(alpha = 0.15f),
                radius = radius * 1.4f,
                center = Offset(node.x, node.y)
            )
            
            // Orbital ring for active node
            if (isActiveNode) {
                drawCircle(
                    color = nodeColor.copy(alpha = 0.4f),
                    radius = radius * 1.8f,
                    center = Offset(node.x, node.y),
                    style = Stroke(width = 2f)
                )
            }
            
            // Outer ring
            drawCircle(
                color = nodeColor,
                radius = radius + 2f,
                center = Offset(node.x, node.y)
            )
            
            // Inner circle (Solid dark)
            drawCircle(
                color = Void,
                radius = radius,
                center = Offset(node.x, node.y)
            )
            
            // Inner core glow
            drawCircle(
                color = nodeColor.copy(alpha = if (isActiveNode) 0.6f else 0.3f),
                radius = radius * 0.6f,
                center = Offset(node.x, node.y)
            )

            // Text Label
            val textLayoutResult = labelLayouts[i]

            // Label background for readability
            val tw = textLayoutResult.size.width
            val th = textLayoutResult.size.height
            val tx = node.x - tw / 2f
            val ty = node.y + 28f
            
            drawRoundRect(
                color = Void.copy(alpha = 0.85f),
                topLeft = Offset(tx - 6f, ty - 2f),
                size = Size(tw + 12f, th + 4f),
                cornerRadius = CornerRadius(4f)
            )

            drawText(
                textLayoutResult = textLayoutResult,
                topLeft = Offset(tx, ty)
            )
        }
    }
}

/** Node colour by category, from the Theme page palette (Figma: Theme › Kortex — Colors, Data colours). */
internal fun NodeCategory.color(): Color = when (this) {
    NodeCategory.IDENTITY -> Synapse
    NodeCategory.EVENT -> Amber
    NodeCategory.KNOWLEDGE -> Teal
    NodeCategory.ASSET -> Muted
}
