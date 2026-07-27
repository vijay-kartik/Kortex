package dev.kortex.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.res.painterResource
import dev.kortex.app.R
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.kortex.graph_core.EdgeType
import dev.kortex.graph_core.NodeType
import dev.kortex.graph_storage.EdgeEntity
import dev.kortex.graph_storage.GraphRegistryEntity
import dev.kortex.graph_storage.business.AssertionEntity
import dev.kortex.graph_storage.business.PersonEntity
import dev.kortex.graph_storage.business.TopicEntity
import io.objectbox.BoxStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
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

class GraphViewModel(application: Application) : AndroidViewModel(application) {
    private val boxStore = (application as dev.kortex.app.KortexApp).container.boxStore
    
    private val _nodes = MutableStateFlow<List<GraphUiNode>>(emptyList())
    val nodes: StateFlow<List<GraphUiNode>> = _nodes.asStateFlow()

    private val _edges = MutableStateFlow<List<GraphUiEdge>>(emptyList())
    val edges: StateFlow<List<GraphUiEdge>> = _edges.asStateFlow()

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
            
            val registryBox = boxStore.boxFor(GraphRegistryEntity::class.java)
            val reg = registryBox.all.find { it.graphKey == nodeId }
            
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
        }
    }

    fun resetGraph() {
        viewModelScope.launch(Dispatchers.IO) {
            boxStore.runInTx {
                boxStore.boxFor(GraphRegistryEntity::class.java).removeAll()
                boxStore.boxFor(EdgeEntity::class.java).removeAll()
                boxStore.boxFor(dev.kortex.graph_storage.EmbeddingEntity::class.java).removeAll()
                boxStore.boxFor(PersonEntity::class.java).removeAll()
                boxStore.boxFor(TopicEntity::class.java).removeAll()
                boxStore.boxFor(AssertionEntity::class.java).removeAll()
            }
            loadGraph()
        }
    }
}

@Composable
fun GraphScreen(vm: GraphViewModel) {
    val nodes by vm.nodes.collectAsState()
    val edges by vm.edges.collectAsState()
    val selectedNodeDetails by vm.selectedNodeDetails.collectAsState()
    var showResetDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        vm.loadGraph()
    }

    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF090A0C))) {
        if (nodes.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text("Graph is empty.", color = Color(0xFF587291))
            }
        } else {
            ForceDirectedGraphCanvas(nodes, edges) { nodeId ->
                vm.selectNode(nodeId)
            }
        }

        // HUD Overlay
        androidx.compose.foundation.layout.Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(24.dp)
                .background(Color(0xFF16191E).copy(alpha = 0.8f), shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                .border(1.dp, Color(0xFF2A2F3A), androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                .padding(16.dp)
        ) {
            Text("KORTEX SEMANTIC NETWORK", style = MaterialTheme.typography.labelMedium.copy(color = Color(0xFFE2C044), letterSpacing = 1.sp))
            Text("NODES: ${nodes.size} // EDGES: ${edges.size}", style = MaterialTheme.typography.labelSmall.copy(color = Color(0xFF8B949E), letterSpacing = 1.sp), modifier = Modifier.padding(top = 4.dp))
        }

        selectedNodeDetails?.let { details ->
            androidx.compose.foundation.layout.Column(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(24.dp)
                    .background(Color(0xFF16191E).copy(alpha = 0.95f), shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                    .border(1.dp, Color(0xFF2A2F3A), androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                    .padding(16.dp)
            ) {
                Text(details.node.type.name, style = MaterialTheme.typography.labelSmall.copy(color = Color(0xFFE2C044), letterSpacing = 1.sp))
                Text(details.node.label, style = MaterialTheme.typography.titleMedium.copy(color = Color.White), modifier = Modifier.padding(top = 4.dp, bottom = 8.dp))
                
                details.details.forEach { (k, v) ->
                    Text("$k:", style = MaterialTheme.typography.labelSmall.copy(color = Color(0xFF8B949E)))
                    Text(v, style = MaterialTheme.typography.bodySmall.copy(color = Color(0xFFC5C6C7)), modifier = Modifier.padding(bottom = 6.dp))
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
                tint = Color(0xFF8B949E)
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
                    Text("Reset", color = Color.Red)
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
    
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var draggedNodeId by remember { mutableStateOf<Long?>(null) }

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

    // Force-directed layout physics loop
    LaunchedEffect(nodes, edges) {
        if (nodes.isEmpty()) return@LaunchedEffect
        while (isActive) {
            val k = 0.015f // Weaker spring constant
            val repulsion = 80000f // Higher repulsion constant for infinite canvas
            val damping = 0.85f // Damping to stabilize

            // Repulsion between all nodes
            for (i in nodes.indices) {
                for (j in i + 1 until nodes.size) {
                    val n1 = nodes[i]
                    val n2 = nodes[j]
                    val dx = n1.x - n2.x
                    val dy = n1.y - n2.y
                    val distSq = max(dx * dx + dy * dy, 10f)
                    val dist = sqrt(distSq)
                    val force = repulsion / distSq
                    
                    val fx = force * (dx / dist)
                    val fy = force * (dy / dist)
                    
                    n1.vx += fx
                    n1.vy += fy
                    n2.vx -= fx
                    n2.vy -= fy
                }
            }

            // Attraction along edges
            for (edge in edges) {
                val n1 = nodes.find { it.id == edge.sourceId } ?: continue
                val n2 = nodes.find { it.id == edge.targetId } ?: continue
                val dx = n2.x - n1.x
                val dy = n2.y - n1.y
                val dist = max(sqrt(dx * dx + dy * dy), 1f)
                val targetDist = 400f
                val force = (dist - targetDist) * k
                
                val fx = force * (dx / dist)
                val fy = force * (dy / dist)
                
                n1.vx += fx
                n1.vy += fy
                n2.vx -= fx
                n2.vy -= fy
            }

            // Center gravity to keep graph on screen
            val cx = canvasWidth / 2f
            val cy = canvasHeight / 2f
            val gravity = 0.002f // Very weak gravity to allow expansion
            for (node in nodes) {
                node.vx += (cx - node.x) * gravity
                node.vy += (cy - node.y) * gravity
            }

            // Apply velocity
            for (node in nodes) {
                if (node.id == draggedNodeId) {
                    node.vx = 0f
                    node.vy = 0f
                    continue
                }
                node.vx *= damping
                node.vy *= damping
                node.x += node.vx
                node.y += node.vy
                
                // Boundaries
                // Infinite Canvas Boundaries
                node.x = node.x.coerceIn(-10000f, 10000f)
                node.y = node.y.coerceIn(-10000f, 10000f)
            }

            delay(16) // ~60fps
        }
    }


    
    // Subtle ambient pulsing animation for nodes
    val infiniteTransition = rememberInfiniteTransition()
    val pulseRatio by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        )
    )

    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF090A0C))
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
                            onNodeSelected(clicked.id)
                        } else {
                            onNodeSelected(null)
                        }
                    },
                    onDragEnd = { draggedNodeId = null },
                    onDragCancel = { draggedNodeId = null }
                ) { change, dragAmount ->
                    change.consume()
                    draggedNodeId?.let { id ->
                        val node = nodes.find { it.id == id }
                        if (node != null) {
                            node.x += dragAmount.x / scale
                            node.y += dragAmount.y / scale
                            node.vx = 0f
                            node.vy = 0f
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

        // Draw edges (curved paths)
        for (edge in edges) {
            val source = nodes.find { it.id == edge.sourceId }
            val target = nodes.find { it.id == edge.targetId }
            if (source != null && target != null) {
                val isActiveEdge = draggedNodeId != null && (source.id == draggedNodeId || target.id == draggedNodeId)
                val edgeColor = if (isActiveEdge) Color(0xFF587291).copy(alpha = 0.8f) else Color(0xFF2A2F3A).copy(alpha = 0.6f)
                
                val path = androidx.compose.ui.graphics.Path().apply {
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
        }

        // Draw nodes
        for (node in nodes) {
            val nodeColor = when (node.type.category) {
                dev.kortex.graph_core.NodeCategory.IDENTITY -> Color(0xFFE2C044) // Gold
                dev.kortex.graph_core.NodeCategory.EVENT -> Color(0xFFD96C06) // Terracotta
                dev.kortex.graph_core.NodeCategory.KNOWLEDGE -> Color(0xFF587291) // Slate Blue
                else -> Color(0xFF8B949E) // Gray
            }

            val isActiveNode = node.id == draggedNodeId
            val radius = 20f
            
            // Ambient pulse effect
            drawCircle(
                color = nodeColor.copy(alpha = 0.15f),
                radius = radius * 1.4f * pulseRatio,
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
                color = Color(0xFF090A0C),
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
            val textLayoutResult = textMeasurer.measure(
                text = node.label.uppercase(),
                style = TextStyle(color = Color(0xFFC5C6C7), fontSize = 10.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium, letterSpacing = 0.5.sp)
            )
            
            // Label background for readability
            val tw = textLayoutResult.size.width
            val th = textLayoutResult.size.height
            val tx = node.x - tw / 2f
            val ty = node.y + 28f
            
            drawRoundRect(
                color = Color(0xFF090A0C).copy(alpha = 0.85f),
                topLeft = Offset(tx - 6f, ty - 2f),
                size = androidx.compose.ui.geometry.Size(tw + 12f, th + 4f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(4f)
            )

            drawText(
                textLayoutResult = textLayoutResult,
                topLeft = Offset(tx, ty)
            )
        }
    }
}
