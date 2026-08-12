package dev.kortex.core.graph

import dev.kortex.core.llm.LlmProvider
import dev.kortex.core.llm.LlmRequest
import dev.kortex.core.llm.LlmResponse
import dev.kortex.core.llm.LlmChunk
import dev.kortex.core.log.Logger
import dev.kortex.core.log.e
import dev.kortex.core.state.Message
import dev.kortex.core.state.ToolCall
import dev.kortex.core.tool.ToolGovernor
import dev.kortex.core.tool.ToolRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect

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
    /** Hooks for rendering the assistant's final answer as provider deltas arrive. */
    val onTextStreamStarted: () -> Unit = {},
    val onTextDelta: TextDeltaListener = TextDeltaListener {},
    val onTextStreamDiscarded: () -> Unit = {},
    /** Emits an answer only after the graph's review/revision loop has accepted it. */
    val onFinalAnswer: FinalAnswerListener = FinalAnswerListener {},
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
suspend fun AgentContext.complete(
    req: LlmRequest,
    streamText: Boolean = false,
    deferTextUntilFinal: Boolean = false,
): LlmResponse {
    val response = try {
        if (streamText && llm.supportsStreaming) stream(req, deferTextUntilFinal) else llm.complete(req, logger)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        logger.e(TAG, "LLM call failed: ${e.message}", e)
        LlmResponse(message = Message(Message.Role.ASSISTANT, "I ran into an error talking to the model (${e.message ?: e::class.simpleName}). Please try again."))
    }
    onLlmUsage.report(LlmUsage(req.model, response.inputTokens, response.outputTokens))
    return response
}

/** Collects OpenAI-compatible SSE chunks into the message shape used by graph nodes. */
private suspend fun AgentContext.stream(req: LlmRequest, deferTextUntilFinal: Boolean): LlmResponse {
    val text = StringBuilder()
    val textDeltas = mutableListOf<String>()
    val toolCalls = linkedMapOf<Int, MutableToolCall>()
    if (!deferTextUntilFinal) onTextStreamStarted()
    llm.stream(req, logger).collect { chunk ->
        when (chunk) {
            is LlmChunk.Text -> {
                text.append(chunk.delta)
                if (deferTextUntilFinal) textDeltas += chunk.delta else onTextDelta.report(chunk.delta)
            }
            is LlmChunk.ToolCallDelta -> {
                val call = toolCalls.getOrPut(chunk.index) { MutableToolCall() }
                chunk.id?.let { call.id = it }
                chunk.name?.let { call.name = it }
                call.arguments.append(chunk.argsDelta)
            }
            LlmChunk.Done -> Unit
        }
    }
    if (deferTextUntilFinal) {
        if (toolCalls.isNotEmpty()) {
            onTextStreamDiscarded()
        } else {
            // A tool-capable request is only a user-facing answer once it finishes without
            // tool calls. Replay its received chunks so Compose can render every update.
            onTextStreamStarted()
            textDeltas.forEach { delta ->
                onTextDelta.report(delta)
                delay(12)
            }
        }
    }
    return LlmResponse(
        message = Message(
            role = Message.Role.ASSISTANT,
            content = text.toString(),
            toolCalls = toolCalls.values.mapNotNull { call ->
                call.id?.takeIf { call.name != null }?.let { ToolCall(it, call.name!!, call.arguments.toString()) }
            },
        ),
    )
}

private class MutableToolCall {
    var id: String? = null
    var name: String? = null
    val arguments = StringBuilder()
}

private const val TAG = "AgentContext"

/** One LLM call's token usage. */
data class LlmUsage(val model: String, val inputTokens: Int, val outputTokens: Int)

fun interface LlmUsageListener {
    fun report(usage: LlmUsage)
}

fun interface TextDeltaListener {
    fun report(delta: String)
}

fun interface FinalAnswerListener {
    suspend fun report(content: String)
}

/** Returns true if the high-risk action is approved. The Android app shows a Compose sheet. */
fun interface Approver {
    suspend fun approve(toolName: String, argumentsJson: String): Boolean
}

/** A human-readable status emitted as the agent works, e.g. "Tool usage: web_search". */
fun interface ProgressListener {
    fun report(status: String)
}
