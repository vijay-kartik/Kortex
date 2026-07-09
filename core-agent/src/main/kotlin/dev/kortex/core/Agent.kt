package dev.kortex.core

import dev.kortex.core.graph.AgentContext
import dev.kortex.core.graph.END
import dev.kortex.core.graph.agentGraph
import dev.kortex.core.pattern.DirectAnswerNode
import dev.kortex.core.pattern.ReActNode
import dev.kortex.core.pattern.ReflectNode
import dev.kortex.core.pattern.RouterNode
import dev.kortex.core.prompt.SystemPrompt
import dev.kortex.core.state.AgentState
import dev.kortex.core.state.Goal
import dev.kortex.core.state.Message
import java.time.ZonedDateTime

/**
 * Top-level entry point. Builds the agent graph and runs a query. Patterns compose here:
 *
 *   router ──simple_qa──▶ direct ───────────────▶ END        (cheap, no tools)
 *   router ──else───────▶ react ──▶ reflect ──ok─▶ END        (tool loop + critique)
 *                                      ▲           │
 *                                      └──revise───┘           (Reflection cycle)
 *
 * Routing (2) picks the strategy; ReAct (5+17) does tool work; Reflection (4) reviews and
 * loops back until the answer is good or the reflection/budget cap is hit. New patterns
 * (Planner, Supervisor) slot in as more nodes/edges without changing `ask`.
 */
class Agent(private val ctx: AgentContext) {

    private val graph = agentGraph {
        node("router", RouterNode())
        node("direct", DirectAnswerNode())
        node("react", ReActNode())
        node("reflect", ReflectNode())
        entry("router")

        // Routing (pattern 2): branch on the label the router stored in scratch["route"].
        edge("router", "direct") { it.scratch["route"] == "simple_qa" }
        edge("router", "react")  // tool_task / plan (fallthrough)
        edge("direct", END)

        // Reflection (pattern 4): react -> reflect, loop back on "revise", else finish.
        edge("react", "reflect")
        edge("reflect", "react") { it.scratch[ReflectNode.VERDICT] == ReflectNode.REVISE }
        edge("reflect", END)
    }

    suspend fun ask(query: String, attachments: List<dev.kortex.core.state.Attachment> = emptyList(), history: List<Message> = emptyList(), system: String = SystemPrompt.DEFAULT): AgentState {
        // Grounds the model in the real wall-clock time, computed fresh per call (see
        // SystemPrompt.grounded for why the current_time tool alone isn't enough), and
        // in the live tool inventory so the prompt never drifts from the registry.
        val grounded = SystemPrompt.grounded(system, ZonedDateTime.now(), ctx.tools)
        val initial = AgentState(
            messages = listOf(Message(Message.Role.SYSTEM, grounded)) + history + listOf(
                Message(Message.Role.USER, query, attachments),
            ),
            goal = Goal(query),
        )
        return graph.invoke(ctx, initial)
    }

    companion object {
        @Deprecated(
            "Prompts live in the prompt package now.",
            ReplaceWith("SystemPrompt.DEFAULT", "dev.kortex.core.prompt.SystemPrompt"),
        )
        const val DEFAULT_SYSTEM = SystemPrompt.DEFAULT
    }
}
