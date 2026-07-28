package dev.kortex.core.observability

import kotlinx.serialization.Serializable

/**
 * A structured record of one agent run (a single `Agent.ask`). This is the
 * source of truth the dedicated Run-trace screen renders — deliberately owned by
 * core-agent so the content never depends on any app-module class or screen.
 *
 * Superset of the inline chat trace: adds structured, timed [spans], the router
 * [route], run [status], stable [id] (for prev/next navigation), and a link back
 * to the originating chat [sessionId].
 */
@Serializable
data class AgentRun(
    val id: String,
    val query: String,
    val startedAt: Long,
    val endedAt: Long,
    val status: Status,
    val answer: String?,
    val route: String?,
    val stats: RunStats,
    /** The ordered, timed timeline: nodes, LLM calls, tool calls, verdicts, errors. */
    val spans: List<RunSpan>,
    /** The human-readable reasoning stream (mirrors what the live panel shows). */
    val logLines: List<LogLine>,
    val sessionId: String? = null,
) {
    enum class Status { COMPLETED, FAILED, CANCELLED }
}

@Serializable
data class RunStats(
    val durationMs: Long,
    val totalTokens: Int,
    val inputTokens: Int,
    val outputTokens: Int,
    val toolCalls: Int,
    val steps: Int,
    val llmCalls: Int,
)

/** One timed event in the run. Rich payloads ([llm]/[tool]) are set by kind. */
@Serializable
data class RunSpan(
    val kind: Kind,
    val name: String,
    val detail: String,
    val startedAt: Long,
    val durationMs: Long,
    val ok: Boolean = true,
    val llm: LlmCall? = null,
    val tool: ToolCallInfo? = null,
) {
    enum class Kind { NODE, LLM, TOOL, VERDICT, ERROR }
}

@Serializable
data class LlmCall(
    val model: String,
    val inputTokens: Int,
    val outputTokens: Int,
)

@Serializable
data class ToolCallInfo(
    val name: String,
    val argumentsJson: String,
    val decision: String,
    val ok: Boolean,
    val resultPreview: String,
)

@Serializable
data class LogLine(
    val level: String,
    val tag: String,
    val message: String,
    val at: Long,
)

/** Cheap projection for the list screen — no spans/logs loaded. */
@Serializable
data class AgentRunSummary(
    val id: String,
    val query: String,
    val startedAt: Long,
    val status: AgentRun.Status,
    val durationMs: Long,
    val totalTokens: Int,
    val toolCalls: Int,
    val steps: Int,
)
