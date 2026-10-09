package dev.kortex.app.domain.share

import dev.kortex.core.Agent
import dev.kortex.core.graph.AgentContext
import dev.kortex.core.graph.Approver
import dev.kortex.core.graph.LlmUsageListener
import dev.kortex.core.llm.LlmProvider
import dev.kortex.core.log.Logger
import dev.kortex.core.observability.AgentRun
import dev.kortex.core.observability.AgentRunRecorder
import dev.kortex.core.observability.AgentRunStore
import dev.kortex.core.state.AgentState
import dev.kortex.core.state.Attachment
import dev.kortex.core.state.Message
import dev.kortex.core.tool.ToolGovernor
import dev.kortex.core.tool.ToolRegistry
import dev.kortex.app.domain.chat.ChatSessionRepository
import dev.kortex.app.domain.chat.ChatTurn
import dev.kortex.app.domain.chat.ReasoningLine
import dev.kortex.app.domain.chat.ReasoningStats
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Runs the agent for share-intake requests after the floating composer is dismissed —
 * the user has moved on, so the run is app-scoped (survives the activity), the result
 * is persisted as a regular chat session, and [notifier] brings the user back to it.
 *
 * Runs headless: there is no UI to host the Human-in-the-Loop dialog, so the approver
 * declines MEDIUM/HIGH-risk tools; the agent is told so and works with LOW-risk ones.
 */
class ShareAgentRunner(
    private val scope: CoroutineScope,
    private val llm: LlmProvider,
    private val tools: ToolRegistry,
    private val sessions: ChatSessionRepository,
    private val runStore: AgentRunStore,
    private val notifier: ShareResultNotifier,
    private val logger: Logger,
    private val now: () -> Long = System::currentTimeMillis,
    private val ask: suspend (AgentContext, String, Attachment) -> AgentState =
        { ctx, query, attachment -> Agent(ctx).ask(query, listOf(attachment)) },
) {
    /** Fire-and-forget: starts the run and immediately returns the new session's id. */
    fun submit(query: String, attachment: Attachment): String {
        val sessionId = UUID.randomUUID().toString()
        val title = query.ifBlank { attachment.filename ?: "Shared ${kindOf(attachment)}" }.take(40)

        scope.launch {
            val run = Run(query, sessionId)
            val outcome = runCatching { ask(run.context(), query, attachment) }
            val record = outcome.fold(
                onSuccess = { run.recorder.finish(it, AgentRun.Status.COMPLETED) },
                onFailure = { run.recorder.finish(AgentState(), AgentRun.Status.FAILED) },
            )
            runCatching { runStore.save(record) }

            val turns = toTurns(Message(Message.Role.USER, query, listOf(attachment)), outcome, run)
            sessions.save(sessionId, turns, fallbackTitle = title)
            notifier.notifyDone(sessionId, title, turns.last().message.content)
        }
        return sessionId
    }

    /** One run's recorder and the reasoning/token/tool-call tallies shown on its answer turn. */
    private inner class Run(query: String, sessionId: String) {
        val reasoning = mutableListOf<ReasoningLine>()
        var tokens = 0
        var toolCalls = 0
        val startMs = now()
        val recorder = AgentRunRecorder(
            query = query,
            sessionId = sessionId,
            delegate = Logger { level, tag, message, error ->
                logger.log(level, tag, message, error)
                reasoning += ReasoningLine(level, tag, message)
            },
            now = now,
        )

        fun context() = AgentContext(
            llm = llm,
            tools = tools,
            governor = ToolGovernor(onAudit = { entry -> toolCalls++; recorder.audit(entry) }),
            // Headless: no one is there to approve, so risky tools are declined.
            approver = Approver { _, _ -> false },
            logger = recorder.logger,
            onLlmUsage = LlmUsageListener { usage ->
                tokens += usage.inputTokens + usage.outputTokens
                recorder.usage.report(usage)
            },
        )
    }

    private fun toTurns(userMessage: Message, outcome: Result<AgentState>, run: Run): List<ChatTurn> = outcome.fold(
        onSuccess = { result ->
            val answer = result.messages
                .lastOrNull { it.role == Message.Role.ASSISTANT && it.content.isNotBlank() }
                ?: Message(Message.Role.ASSISTANT, "The agent finished without producing an answer.")
            val stats = ReasoningStats(run.tokens, run.toolCalls, now() - run.startMs)
            listOf(ChatTurn(userMessage), ChatTurn(answer, run.reasoning.toList(), stats))
        },
        onFailure = { err ->
            logger.log(Logger.Level.ERROR, TAG, "share run failed: ${err.message}", err)
            listOf(
                ChatTurn(userMessage),
                ChatTurn(Message(Message.Role.ASSISTANT, "Processing failed: ${err.message ?: "unknown error"}. Open the session and retry from the chat.")),
            )
        },
    )

    private fun kindOf(att: Attachment) = when {
        att.mimeType.startsWith("image/") -> "photo"
        att.mimeType == "application/pdf" -> "PDF"
        else -> "file"
    }

    companion object {
        private const val TAG = "ShareAgentRunner"
    }
}
