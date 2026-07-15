package dev.kortex.core

import dev.kortex.core.graph.AgentContext
import dev.kortex.core.graph.END
import dev.kortex.core.graph.agentGraph
import dev.kortex.core.pattern.DirectAnswerNode
import dev.kortex.core.pattern.ExecuteStepNode
import dev.kortex.core.pattern.PlanNode
import dev.kortex.core.pattern.ReActNode
import dev.kortex.core.pattern.ReflectNode
import dev.kortex.core.pattern.RouterNode
import dev.kortex.core.pattern.SynthesizeNode
import dev.kortex.core.prompt.SystemPrompt
import dev.kortex.core.state.AgentState
import dev.kortex.core.state.Goal
import dev.kortex.core.state.Message
import java.time.ZonedDateTime

/**
 * Top-level entry point. Builds the agent graph and runs a query. Patterns compose here:
 *
 *   router ──simple_qa──▶ direct ─────────────────────────▶ END   (cheap, no tools)
 *   router ──tool_task──▶ react ──▶ reflect ──ok──▶ END           (tool loop + critique)
 *   router ──plan───────▶ plan ──▶ execute ─┐                     (T4.2, pattern 6)
 *                           │       ▲       │ (steps remain)
 *                           │       └───────┘
 *                           │(degraded)     │ (all steps done)
 *                           └──▶ react      ▼
 *                                       synthesize ──▶ reflect ──ok──▶ END
 *                                           ▲              │
 *                                           └────revise────┘
 *
 * Routing (2) picks the strategy; ReAct (5+17) does tool work; Planning (6) decomposes
 * multi-goal requests into scoped steps; Reflection (4) reviews and loops back until the
 * answer is good or the reflection/budget cap is hit. Reflect's revise edge for plan runs
 * targets synthesize, not execute — re-answering from collected evidence is cheap;
 * re-running tools is not.
 */
class Agent(private val ctx: AgentContext) {

    private val graph = agentGraph {
        node("router", RouterNode())
        node("direct", DirectAnswerNode())
        node("react", ReActNode())
        node("reflect", ReflectNode())
        node("plan", PlanNode())
        node("execute", ExecuteStepNode())
        node("synthesize", SynthesizeNode())
        entry("router")

        // Routing (pattern 2): branch on the label the router stored in scratch["route"].
        edge("router", "direct") { it.scratch["route"] == "simple_qa" }
        edge("router", "plan") { it.scratch["route"] == "plan" }
        edge("router", "react")  // tool_task (fallthrough)
        edge("direct", END)

        // Planning (pattern 6, T4.2): decompose, then execute one step per pass; when no
        // plan was set (parse failure / too few steps) fall through to plain react — a
        // broken planner must never make the agent worse than the react path.
        edge("plan", "execute") { it.plan != null }
        edge("plan", "react")  // degradation fallthrough
        edge("execute", "execute") { it.plan?.nextPending != null }
        edge("execute", "synthesize")  // all steps done/failed
        edge("synthesize", "reflect")

        // Reflection (pattern 4): loop back on "revise" — to synthesize when a plan exists
        // (edge order matters: this must precede the react revise edge), else to react.
        edge("react", "reflect")
        edge("reflect", "synthesize") {
            it.scratch[ReflectNode.VERDICT] == ReflectNode.REVISE && it.plan != null
        }
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
