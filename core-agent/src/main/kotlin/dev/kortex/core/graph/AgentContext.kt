package dev.kortex.core.graph

import dev.kortex.core.llm.LlmProvider
import dev.kortex.core.llm.LlmRequest
import dev.kortex.core.llm.LlmResponse
import dev.kortex.core.log.Logger
import dev.kortex.core.log.e
import dev.kortex.core.state.Message
import dev.kortex.core.tool.ToolGovernor
import dev.kortex.core.tool.ToolRegistry
import kotlinx.coroutines.CancellationException

/**
 * Shared services a node may use while running. Kept out of [dev.kortex.core.state.AgentState]
 * because these are dependencies, not data that should be serialized/checkpointed.
 */
class AgentContext(
    val llm: LlmProvider,
    val tools: ToolRegistry,
    val governor: ToolGovernor,
    /** Hook for Human-in-the-Loop (pattern 13): suspends until the UI approves/denies. */
    val approver: Approver = Approver { _, _ -> true },
    /** Live progress hook so the UI can show what the agent is doing right now. */
    val onProgress: ProgressListener = ProgressListener {},
    /** Structured logging for requests/responses/tool calls (pattern 19: Evaluation & Monitoring). */
    val logger: Logger = Logger.CONSOLE,
    /** Structured token usage per LLM call (pattern 19) — drives live cost/stats UIs without
     *  needing to parse log strings. Fired by [AgentContext.complete] after every call. */
    val onLlmUsage: LlmUsageListener = LlmUsageListener {},
)

/**
 * The one way nodes should call the LLM: threads the context's logger into the provider
 * and reports structured usage. Calling `ctx.llm.complete` directly bypasses both.
 *
 * A provider failure (network error, malformed response, rate limit, ...) is turned into
 * a plain assistant message describing the failure rather than propagating — every node
 * (Router, ReAct, Reflect, ...) already degrades gracefully on unexpected message content
 * (see e.g. RouterNode's `?: routes.first()` fallback), so this ends the turn cleanly
 * instead of crashing the whole app the way an uncaught exception here used to.
 */
suspend fun AgentContext.complete(req: LlmRequest): LlmResponse {
    val response = try {
        llm.complete(req, logger)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        logger.e(TAG, "LLM call failed: ${e.message}", e)
        LlmResponse(message = Message(Message.Role.ASSISTANT, "I ran into an error talking to the model (${e.message ?: e::class.simpleName}). Please try again."))
    }
    onLlmUsage.report(LlmUsage(req.model, response.inputTokens, response.outputTokens))
    return response
}

private const val TAG = "AgentContext"

/** One LLM call's token usage. */
data class LlmUsage(val model: String, val inputTokens: Int, val outputTokens: Int)

fun interface LlmUsageListener {
    fun report(usage: LlmUsage)
}

/** Returns true if the high-risk action is approved. The Android app shows a Compose sheet. */
fun interface Approver {
    suspend fun approve(toolName: String, argumentsJson: String): Boolean
}

/** A human-readable status emitted as the agent works, e.g. "Tool usage: web_search". */
fun interface ProgressListener {
    fun report(status: String)
}
