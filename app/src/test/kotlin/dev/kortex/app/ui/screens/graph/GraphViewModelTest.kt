package dev.kortex.app.ui.screens.graph

import dev.kortex.app.domain.graph.GraphSnapshot
import dev.kortex.app.domain.graph.GraphSnapshotEdge
import dev.kortex.app.domain.graph.GraphSnapshotNode
import dev.kortex.app.domain.graph.GraphSnapshotSource
import dev.kortex.graph_core.EdgeType
import dev.kortex.graph_core.NodeType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The Graph tab's load, node selection and reset, with the graph store faked. */
@OptIn(ExperimentalCoroutinesApi::class)
class GraphViewModelTest {

    private val source = FakeGraphSnapshotSource()

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `loading draws every node and edge from the source`() = runTest {
        val vm = GraphViewModel(source)
        assertFalse(vm.loaded.value)

        vm.loadGraph()

        assertTrue(vm.loaded.value)
        assertEquals(listOf(1L to "Kartik", 2L to "works at", 3L to "Acme"), vm.nodes.value.map { it.id to it.label })
        assertEquals(listOf(NodeType.PERSON, NodeType.ASSERTION, NodeType.TOPIC), vm.nodes.value.map { it.type })
        assertEquals(
            listOf(GraphUiEdge(2, 1, EdgeType.SUBJECT), GraphUiEdge(2, 3, EdgeType.OBJECT)),
            vm.edges.value,
        )
    }

    @Test
    fun `an empty graph still counts as loaded`() = runTest {
        source.graph = GraphSnapshot(emptyList(), emptyList())
        val vm = GraphViewModel(source)

        vm.loadGraph()

        assertTrue(vm.loaded.value)
        assertTrue(vm.nodes.value.isEmpty())
    }

    @Test
    fun `selecting a node shows its details and the edges that touch it`() = runTest {
        val vm = GraphViewModel(source).apply { loadGraph() }

        vm.selectNode(1)

        val selected = vm.selectedNodeDetails.value!!
        assertEquals(1L, selected.node.id)
        assertEquals(listOf(GraphUiEdge(2, 1, EdgeType.SUBJECT)), selected.connectedEdges)
        assertEquals(mapOf("Name" to "Kartik"), selected.details)
    }

    @Test
    fun `selecting nothing or an unknown node clears the selection`() = runTest {
        val vm = GraphViewModel(source).apply { loadGraph() }
        vm.selectNode(1)

        vm.selectNode(null)
        assertNull(vm.selectedNodeDetails.value)

        vm.selectNode(99)
        assertNull(vm.selectedNodeDetails.value)
    }

    @Test
    fun `reset clears the store and reloads the now empty graph`() = runTest {
        val vm = GraphViewModel(source).apply { loadGraph() }

        vm.resetGraph()

        assertEquals(1, source.clears)
        assertTrue(vm.nodes.value.isEmpty())
        assertTrue(vm.edges.value.isEmpty())
        assertTrue(vm.loaded.value)
    }
}

private class FakeGraphSnapshotSource : GraphSnapshotSource {
    var graph = GraphSnapshot(
        nodes = listOf(
            GraphSnapshotNode(1, "Kartik", NodeType.PERSON),
            GraphSnapshotNode(2, "works at", NodeType.ASSERTION),
            GraphSnapshotNode(3, "Acme", NodeType.TOPIC),
        ),
        edges = listOf(
            GraphSnapshotEdge(2, 1, EdgeType.SUBJECT),
            GraphSnapshotEdge(2, 3, EdgeType.OBJECT),
        ),
    )
    var clears = 0

    private val details = mapOf(1L to mapOf("Name" to "Kartik"))

    override suspend fun snapshot() = graph

    override suspend fun details(graphKey: Long) = details[graphKey].orEmpty()

    override suspend fun clear() {
        clears++
        graph = GraphSnapshot(emptyList(), emptyList())
    }
}
