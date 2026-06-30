package dev.kortex.core.graph

import dev.kortex.core.state.AgentState

/**
 * The book's central lesson (Appendix C): real agents need cyclic, stateful graphs,
 * not just linear chains. A [Node] transforms state; [Edge]s wire nodes together with
 * optional conditions so the graph can branch, loop, retry, and reflect.
 *
 * Patterns realised as graph shapes:
 *   - Prompt Chaining (1): a straight line of nodes.
 *   - Routing (2): conditional edges out of a router node.
 *   - Reflection (4): an edge that loops back until a quality check passes.
 *   - Planning (6): plan node -> execute node -> edge back to plan.
 */
fun interface Node {
    suspend fun run(ctx: AgentContext, state: AgentState): AgentState
}

/** Sentinel for the implicit terminal node. */
const val END = "__end__"

class Edge(
    val from: String,
    val to: String,
    /** First edge whose predicate is true is taken; default `{ true }` is the fallthrough. */
    val condition: (AgentState) -> Boolean = { true },
)

class AgentGraph internal constructor(
    private val entry: String,
    private val nodes: Map<String, Node>,
    private val edges: Map<String, List<Edge>>,
) {
    suspend fun invoke(ctx: AgentContext, initial: AgentState): AgentState {
        var current = entry
        var state = initial
        while (current != END) {
            val node = nodes[current] ?: error("No node '$current' in graph")
            state = node.run(ctx, state).let { it.copy(budget = it.budget.copy(steps = it.budget.steps + 1)) }
            // `done` is informational (a final answer is ready); flow to termination is
            // controlled by edges to END so downstream nodes (e.g. Reflection) can still run.
            // Budget is the hard safety stop against runaway loops.
            if (state.budget.exhausted) break
            current = edges[current]?.firstOrNull { it.condition(state) }?.to
                ?: error("No outgoing edge from '$current' matched")
        }
        return state
    }

    class Builder {
        private val nodes = mutableMapOf<String, Node>()
        private val edges = mutableMapOf<String, MutableList<Edge>>()
        private var entry: String? = null

        fun node(name: String, node: Node) = apply { nodes[name] = node }
        fun entry(name: String) = apply { entry = name }
        fun edge(from: String, to: String, condition: (AgentState) -> Boolean = { true }) =
            apply { edges.getOrPut(from) { mutableListOf() }.add(Edge(from, to, condition)) }

        fun build(): AgentGraph =
            AgentGraph(requireNotNull(entry) { "graph needs an entry node" }, nodes, edges)
    }
}

fun agentGraph(block: AgentGraph.Builder.() -> Unit): AgentGraph =
    AgentGraph.Builder().apply(block).build()
