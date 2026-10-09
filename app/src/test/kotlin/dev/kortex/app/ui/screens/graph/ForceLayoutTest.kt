package dev.kortex.app.ui.screens.graph

import dev.kortex.graph_core.EdgeType
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Graph tab's force layout: edges resolve to indices once, and the simulation settles so it can stop. */
class ForceLayoutTest {
    private val anyEdgeType = EdgeType.entries.first()

    @Test
    fun `edges resolve to node indices and dangling edges are dropped`() {
        val nodeIndex = mapOf(10L to 0, 20L to 1, 30L to 2)
        val edges = listOf(
            GraphUiEdge(sourceId = 10L, targetId = 30L, type = anyEdgeType),
            GraphUiEdge(sourceId = 20L, targetId = 99L, type = anyEdgeType),
            GraphUiEdge(sourceId = 30L, targetId = 20L, type = anyEdgeType),
        )

        val resolved = resolveEdges(nodeIndex, edges)

        assertEquals(2, resolved.size)
        assertArrayEquals(intArrayOf(0, 2), resolved.source)
        assertArrayEquals(intArrayOf(2, 1), resolved.target)
    }

    @Test
    fun `pinned node stays put and has no velocity`() {
        val state = LayoutState(2).apply {
            x[0] = 100f; y[0] = 100f
            x[1] = 110f; y[1] = 100f
        }

        stepForceLayout(state, resolveEdges(emptyMap(), emptyList()), pinned = 0, centerX = 0f, centerY = 0f)

        assertEquals(100f, state.x[0])
        assertEquals(100f, state.y[0])
        assertEquals(0f, state.vx[0])
        assertEquals(0f, state.vy[0])
        assertTrue("the free node is pushed away", state.x[1] > 110f)
    }

    @Test
    fun `a small connected graph settles within the iteration bound`() {
        val nodeIndex = (0 until 6).associateBy { it.toLong() }
        val edges = (1 until 6).map { GraphUiEdge(sourceId = 0L, targetId = it.toLong(), type = anyEdgeType) }
        val resolved = resolveEdges(nodeIndex, edges)
        val state = LayoutState(6).apply {
            for (i in 0 until size) {
                x[i] = 500f + i * 37f
                y[i] = 500f + (i * 53 % 7) * 29f
            }
        }

        val settledAt = (1..MAX_LAYOUT_ITERATIONS).firstOrNull {
            val energy = stepForceLayout(state, resolved, pinned = -1, centerX = 500f, centerY = 500f)
            isLayoutSettled(energy, state.size)
        }

        assertTrue("layout never settled", settledAt != null)
    }
}
