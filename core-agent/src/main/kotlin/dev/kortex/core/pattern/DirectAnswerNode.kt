package dev.kortex.core.pattern

import dev.kortex.core.graph.AgentContext
import dev.kortex.core.graph.Node
import dev.kortex.core.llm.LlmRequest
import dev.kortex.core.llm.Models
import dev.kortex.core.state.AgentState

/**
 * The "simple_qa" strategy: a single LLM call with no tools. Used when the RouterNode
 * (pattern 2) decides the query is answerable directly. Runs on the FAST model to save
 * budget (pattern 16: Resource-Aware Optimization) — no point spinning up the ReAct
 * tool loop for "what's the capital of France?".
 */
class DirectAnswerNode(private val model: String = Models.FAST) : Node {
    override suspend fun run(ctx: AgentContext, state: AgentState): AgentState {
        ctx.onProgress.report("Answering…")
        val resp = ctx.llm.complete(LlmRequest(model = model, messages = state.messages))
        return state.withMessage(resp.message)
            .copy(
                done = true,
                budget = state.budget.copy(
                    tokensUsed = state.budget.tokensUsed + resp.inputTokens + resp.outputTokens,
                ),
            )
            .trace("direct", "llm", "answer")
    }
}
