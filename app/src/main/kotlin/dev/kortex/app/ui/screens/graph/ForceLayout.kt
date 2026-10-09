package dev.kortex.app.ui.screens.graph

import kotlin.math.max
import kotlin.math.sqrt

/** Ticks before the layout gives up on settling, so a graph that keeps oscillating still goes idle. */
internal const val MAX_LAYOUT_ITERATIONS = 1000

/** Mean squared node speed (px²/tick) below which the layout counts as settled. */
internal const val SETTLED_ENERGY_PER_NODE = 0.01f

/** Edges resolved to node index pairs once per graph, so the simulation and the draw pass never search the node list. */
internal class ResolvedEdges(val source: IntArray, val target: IntArray) {
    val size: Int get() = source.size
}

/** Edges whose endpoints aren't both among the nodes are dropped, as the old per-tick lookups skipped them. */
internal fun resolveEdges(nodeIndex: Map<Long, Int>, edges: List<GraphUiEdge>): ResolvedEdges {
    val source = ArrayList<Int>(edges.size)
    val target = ArrayList<Int>(edges.size)
    for (edge in edges) {
        val s = nodeIndex[edge.sourceId] ?: continue
        val t = nodeIndex[edge.targetId] ?: continue
        source += s
        target += t
    }
    return ResolvedEdges(source.toIntArray(), target.toIntArray())
}

/**
 * Node positions and velocities by node index. The screen copies them in and out of the
 * nodes on the main thread, so [stepForceLayout] can run on a background thread without
 * racing the draw pass or a drag.
 */
internal class LayoutState(val size: Int) {
    val x = FloatArray(size)
    val y = FloatArray(size)
    val vx = FloatArray(size)
    val vy = FloatArray(size)
}

/**
 * Advances the force-directed layout by one tick and returns the total kinetic energy
 * (Σ vx² + vy²) afterwards. The node at [pinned] (or none, if -1) is held in place.
 */
internal fun stepForceLayout(
    state: LayoutState,
    edges: ResolvedEdges,
    pinned: Int,
    centerX: Float,
    centerY: Float,
): Float {
    val k = 0.015f // Weaker spring constant
    val repulsion = 80000f // Higher repulsion constant for infinite canvas
    val damping = 0.85f // Damping to stabilize
    val x = state.x
    val y = state.y
    val vx = state.vx
    val vy = state.vy

    // Repulsion between all nodes
    for (i in 0 until state.size) {
        for (j in i + 1 until state.size) {
            val dx = x[i] - x[j]
            val dy = y[i] - y[j]
            val distSq = max(dx * dx + dy * dy, 10f)
            val dist = sqrt(distSq)
            val force = repulsion / distSq

            val fx = force * (dx / dist)
            val fy = force * (dy / dist)

            vx[i] += fx
            vy[i] += fy
            vx[j] -= fx
            vy[j] -= fy
        }
    }

    // Attraction along edges
    for (e in 0 until edges.size) {
        val s = edges.source[e]
        val t = edges.target[e]
        val dx = x[t] - x[s]
        val dy = y[t] - y[s]
        val dist = max(sqrt(dx * dx + dy * dy), 1f)
        val targetDist = 400f
        val force = (dist - targetDist) * k

        val fx = force * (dx / dist)
        val fy = force * (dy / dist)

        vx[s] += fx
        vy[s] += fy
        vx[t] -= fx
        vy[t] -= fy
    }

    // Center gravity to keep graph on screen
    val gravity = 0.002f // Very weak gravity to allow expansion
    for (i in 0 until state.size) {
        vx[i] += (centerX - x[i]) * gravity
        vy[i] += (centerY - y[i]) * gravity
    }

    // Apply velocity
    var energy = 0f
    for (i in 0 until state.size) {
        if (i == pinned) {
            vx[i] = 0f
            vy[i] = 0f
            continue
        }
        vx[i] *= damping
        vy[i] *= damping
        x[i] = (x[i] + vx[i]).coerceIn(-10000f, 10000f) // Infinite Canvas Boundaries
        y[i] = (y[i] + vy[i]).coerceIn(-10000f, 10000f)
        energy += vx[i] * vx[i] + vy[i] * vy[i]
    }
    return energy
}

/** True once the layout has calmed down enough to stop ticking. */
internal fun isLayoutSettled(energy: Float, nodeCount: Int): Boolean =
    energy < SETTLED_ENERGY_PER_NODE * nodeCount
