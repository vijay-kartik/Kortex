package dev.kortex.core.observability

import dev.kortex.core.graph.LlmUsage
import dev.kortex.core.graph.LlmUsageListener
import dev.kortex.core.log.Logger
import dev.kortex.core.state.AgentState
import dev.kortex.core.state.Message
import dev.kortex.core.tool.ToolGovernor
import java.util.UUID

/**
 * Captures one agent run into an [AgentRun] by implementing the hooks the agent
 * already exposes — nothing in the graph/nodes changes. Create one per run, wire
 * [logger] / [usage] / [audit] into the [dev.kortex.core.graph.AgentContext] and
 * [ToolGovernor], run the graph, then call [finish] with the terminal state.
 *
 * [logger] delegates to [delegate] so the host's existing logging (Logcat, the
 * live reasoning panel) keeps working while this records structured [LogLine]s.
 *
 * Timing is approximate in this phase: per-span durations are derived from the
 * gap to the next span. Exact timing is a later enrichment (elapsedMs on
 * LlmUsage/AuditEntry + node timing in the graph).
 */
class AgentRunRecorder(
    private val query: String,
    private val sessionId: String? = null,
    private val delegate: Logger = Logger.NONE,
    private val now: () -> Long = System::currentTimeMillis,
    private val idProvider: () -> String = { UUID.randomUUID().toString() },
) {
    private val lock = Any()
    private val startedAt = now()
    private val logs = mutableListOf<LogLine>()
    private val llmSpans = mutableListOf<RunSpan>()
    private val toolSpans = mutableListOf<RunSpan>()
    private var inputTokens = 0
    private var outputTokens = 0
    private var toolCalls = 0
    private var llmCalls = 0

    /** Wire into AgentContext.logger. Records a LogLine and forwards to [delegate]. */
    val logger = Logger { level, tag, message, error ->
        synchronized(lock) { logs += LogLine(level.name, tag, message, now()) }
        delegate.log(level, tag, message, error)
    }

    /** Wire into AgentContext.onLlmUsage. One LLM span per call. */
    val usage = LlmUsageListener { u -> recordUsage(u) }

    /** Wire into ToolGovernor(onAudit = ...). One TOOL span per call. */
    val audit: (ToolGovernor.AuditEntry) -> Unit = { e -> recordAudit(e) }

    private fun recordUsage(u: LlmUsage) = synchronized(lock) {
        llmCalls++
        inputTokens += u.inputTokens
        outputTokens += u.outputTokens
        llmSpans += RunSpan(
            kind = RunSpan.Kind.LLM,
            name = u.model,
            detail = "${u.inputTokens} in / ${u.outputTokens} out",
            startedAt = now(),
            durationMs = 0,
            llm = LlmCall(u.model, u.inputTokens, u.outputTokens),
        )
    }

    private fun recordAudit(e: ToolGovernor.AuditEntry) = synchronized(lock) {
        toolCalls++
        val allowed = e.decision == ToolGovernor.Decision.ALLOWED
        toolSpans += RunSpan(
            kind = RunSpan.Kind.TOOL,
            name = e.tool,
            detail = e.decision.name,
            startedAt = e.at,
            durationMs = 0,
            ok = allowed,
            tool = ToolCallInfo(e.tool, e.argumentsJson, e.decision.name, allowed, resultPreview = ""),
        )
    }

    /** Assembles the final record from the terminal [state] plus recorded spans. */
    fun finish(state: AgentState, status: AgentRun.Status): AgentRun = synchronized(lock) {
        val endedAt = now()

        // Structural markers from the state trace (nodes / verdicts). The "llm"/"tool"
        // trace kinds are dropped — their richer spans come from the usage/audit hooks.
        val nodeSpans = state.trace
            .filter { it.kind != "llm" && it.kind != "tool" }
            .map { ev ->
                RunSpan(
                    kind = if (ev.kind == "verdict") RunSpan.Kind.VERDICT else RunSpan.Kind.NODE,
                    name = ev.node,
                    detail = if (ev.detail.isBlank()) ev.kind else "${ev.kind}: ${ev.detail}",
                    startedAt = ev.at,
                    durationMs = 0,
                )
            }

        val ordered = (nodeSpans + llmSpans + toolSpans).sortedBy { it.startedAt }
        val spans = ordered.mapIndexed { i, span ->
            val next = if (i + 1 < ordered.size) ordered[i + 1].startedAt else endedAt
            span.copy(durationMs = (next - span.startedAt).coerceAtLeast(0))
        }

        val route = state.trace.firstOrNull { it.kind == "route" }?.detail
        val answer = state.messages
            .lastOrNull { it.role == Message.Role.ASSISTANT && it.content.isNotBlank() }
            ?.content

        AgentRun(
            id = idProvider(),
            query = query,
            startedAt = startedAt,
            endedAt = endedAt,
            status = status,
            answer = answer,
            route = route,
            stats = RunStats(
                durationMs = endedAt - startedAt,
                totalTokens = inputTokens + outputTokens,
                inputTokens = inputTokens,
                outputTokens = outputTokens,
                toolCalls = toolCalls,
                steps = state.budget.steps,
                llmCalls = llmCalls,
            ),
            spans = spans,
            logLines = logs.toList(),
            sessionId = sessionId,
        )
    }
}
