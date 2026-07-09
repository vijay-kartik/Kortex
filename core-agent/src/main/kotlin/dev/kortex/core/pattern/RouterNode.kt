package dev.kortex.core.pattern

import dev.kortex.core.graph.AgentContext
import dev.kortex.core.graph.Node
import dev.kortex.core.graph.complete
import dev.kortex.core.llm.LlmRequest
import dev.kortex.core.llm.Models
import dev.kortex.core.log.d
import dev.kortex.core.log.i
import dev.kortex.core.prompt.RouterPrompt
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
        ctx.onProgress.report("Analyzing your request…")
        val query = state.messages.lastOrNull { it.role == Message.Role.USER }?.content.orEmpty()
        ctx.logger.d(TAG, "classifying request: \"$query\"")
        val prompt = RouterPrompt.build(routes, query)

        val resp = ctx.complete(
            LlmRequest(
                model = model,
                messages = listOf(Message(Message.Role.USER, prompt)),
                temperature = 0.0,
            )
        )
        val route = routes.firstOrNull { resp.message.content.trim().contains(it) } ?: routes.first()
        ctx.logger.i(TAG, "routed to '$route'")
        return state.copy(
            scratch = state.scratch + ("route" to route),
            budget = state.budget.copy(
                tokensUsed = state.budget.tokensUsed + resp.inputTokens + resp.outputTokens,
            ),
        ).trace("router", "route", route)
    }

    companion object {
        private const val TAG = "RouterNode"
    }
}
