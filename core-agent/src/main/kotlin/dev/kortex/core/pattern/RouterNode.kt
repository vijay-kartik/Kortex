package dev.kortex.core.pattern

import dev.kortex.core.graph.AgentContext
import dev.kortex.core.graph.Node
import dev.kortex.core.llm.LlmRequest
import dev.kortex.core.llm.Models
import dev.kortex.core.state.AgentState
import dev.kortex.core.state.Message

/**
 * Pattern 2 (Routing). Uses a cheap/fast model to classify the latest user query into a
 * route label, stored in state.scratch["route"]. The graph's conditional edges then send
 * the query to the right sub-strategy (e.g. "simple_qa", "tool_task", "plan").
 *
 * Pairs with pattern 16: classification runs on the FAST model to save budget.
 */
class RouterNode(
    private val routes: List<String> = listOf("simple_qa", "tool_task", "plan"),
    private val model: String = Models.FAST,
) : Node {
    override suspend fun run(ctx: AgentContext, state: AgentState): AgentState {
        val query = state.messages.lastOrNull { it.role == Message.Role.USER }?.content.orEmpty()
        val prompt = """
            Classify the user request into exactly one of: ${routes.joinToString(", ")}.
            - simple_qa: answerable directly, no tools, no multi-step work.
            - tool_task: needs one or a few tool calls.
            - plan: open-ended/multi-step; needs decomposition first.
            Respond with ONLY the label.

            Request: $query
        """.trimIndent()

        val resp = ctx.llm.complete(
            LlmRequest(
                model = model,
                messages = listOf(Message(Message.Role.USER, prompt)),
                temperature = 0.0,
            )
        )
        val route = routes.firstOrNull { resp.message.content.trim().contains(it) } ?: routes.first()
        return state.copy(scratch = state.scratch + ("route" to route))
            .trace("router", "route", route)
    }
}
