package dev.kortex.core

import dev.kortex.core.graph.AgentContext
import dev.kortex.core.graph.END
import dev.kortex.core.graph.agentGraph
import dev.kortex.core.pattern.DirectAnswerNode
import dev.kortex.core.pattern.ReActNode
import dev.kortex.core.pattern.ReflectNode
import dev.kortex.core.pattern.RouterNode
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

    suspend fun ask(query: String, attachments: List<dev.kortex.core.state.Attachment> = emptyList(), system: String = DEFAULT_SYSTEM): AgentState {
        // Grounds the model in the real wall-clock time, computed fresh per call. A model's
        // training cutoff otherwise silently stands in for "today" (e.g. it'll search for
        // "richest person 2023" on a device where it's actually 2026) — the current_time
        // tool alone doesn't fix this because nothing forces the model to call it before
        // deciding how to phrase a time-sensitive query.
        val grounded = "$system\n\nCurrent date and time: ${ZonedDateTime.now()}. Use this to " +
            "interpret words like \"today\", \"current\", \"latest\", or a bare year correctly " +
            "— your training data has a cutoff well before this date, so never assume it is the present."
        val initial = AgentState(
            messages = listOf(
                Message(Message.Role.SYSTEM, grounded),
                Message(Message.Role.USER, query, attachments),
            ),
            goal = Goal(query),
        )
        return graph.invoke(ctx, initial)
    }

    companion object {
        const val DEFAULT_SYSTEM =
            "You are Kortex, a capable on-device agent. When you need to take an action " +
                "(send a message, create an event, etc.), use the appropriate tool immediately. " +
                "Do NOT ask the user for permission in text; the system will automatically " +
                "prompt the user for approval when you call a tool. Explain your reasoning briefly. " +
                "web_search only returns short snippets. If a result's snippet is incomplete, " +
                "references a page with more detail, or is a live/real-time page (e.g. a " +
                "'real-time billionaires list' or a news archive), call open_url on that result's " +
                "URL and read the actual page — never tell the user to visit a link yourself when " +
                "you're able to open it and answer directly."
    }
}
