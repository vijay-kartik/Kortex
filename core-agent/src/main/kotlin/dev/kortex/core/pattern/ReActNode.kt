package dev.kortex.core.pattern

import dev.kortex.core.graph.AgentContext
import dev.kortex.core.graph.Node
import dev.kortex.core.llm.LlmRequest
import dev.kortex.core.llm.Models
import dev.kortex.core.state.AgentState
import dev.kortex.core.state.Message
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Pattern 17 (Reasoning: ReAct) + Pattern 5 (Tool Use).
 * Loops Think -> Act -> Observe: ask the LLM, run any tool calls through the governor,
 * feed observations back, until the model answers with no tool calls (or budget runs out).
 */
class ReActNode(
    private val model: String = Models.REASONING,
    private val maxIterations: Int = 8,
) : Node {
    override suspend fun run(ctx: AgentContext, state: AgentState): AgentState {
        var s = state
        repeat(maxIterations) {
            ctx.onProgress.report("Thinking…")
            val resp = ctx.llm.complete(
                LlmRequest(model = model, messages = s.messages, tools = ctx.tools.all())
            )
            s = s.withMessage(resp.message).copy(
                budget = s.budget.copy(tokensUsed = s.budget.tokensUsed + resp.outputTokens + resp.inputTokens)
            ).trace("react", "llm", "tools=${resp.message.toolCalls.size}")

            if (!resp.wantsTools) return s.copy(done = true)

            for (call in resp.message.toolCalls) {
                ctx.onProgress.report("Tool usage: ${call.name}")
                val tool = ctx.tools.get(call.name)
                val result = if (tool == null) {
                    dev.kortex.core.tool.ToolResult(false, "Unknown tool '${call.name}'")
                } else {
                    val args = runCatching { Json.parseToJsonElement(call.argumentsJson) as JsonObject }
                        .getOrDefault(JsonObject(emptyMap()))
                    ctx.governor.run(tool, args, s.budget, ctx.approver)
                }
                s = s.copy(budget = s.budget.copy(toolCallsMade = s.budget.toolCallsMade + 1))
                    .withMessage(Message(Message.Role.TOOL, result.content, toolCallId = call.id))
                    .trace("react", "tool", "${call.name}->${result.ok}")
            }
            if (s.budget.exhausted) return s.copy(done = true)
        }
        return s.copy(done = true)
    }
}
