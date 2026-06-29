package dev.kortex.core

import dev.kortex.core.graph.AgentContext
import dev.kortex.core.graph.END
import dev.kortex.core.graph.agentGraph
import dev.kortex.core.pattern.ReActNode
import dev.kortex.core.pattern.RouterNode
import dev.kortex.core.state.AgentState
import dev.kortex.core.state.Goal
import dev.kortex.core.state.Message

/**
 * Top-level entry point. Builds the default graph (Routing -> ReAct) and runs a query.
 * As more patterns land (Planner, Reflection, Supervisor), they slot in as extra nodes
 * and edges here — the public surface (`ask`) stays the same.
 */
class Agent(private val ctx: AgentContext) {

    private val graph = agentGraph {
        node("router", RouterNode())
        node("react", ReActNode())
        entry("router")
        // Conditional edges (pattern 2). For now every route funnels to ReAct;
        // "plan" will later route to a PlannerNode, "simple_qa" to a direct-answer node.
        edge("router", "react")
        edge("react", END)
    }

    suspend fun ask(query: String, system: String = DEFAULT_SYSTEM): AgentState {
        val initial = AgentState(
            messages = listOf(
                Message(Message.Role.SYSTEM, system),
                Message(Message.Role.USER, query),
            ),
            goal = Goal(query),
        )
        return graph.invoke(ctx, initial)
    }

    companion object {
        const val DEFAULT_SYSTEM =
            "You are Kortex, a capable on-device agent. Use tools when they help, " +
                "explain your reasoning briefly, and ask for confirmation on risky actions."
    }
}
