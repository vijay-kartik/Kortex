package dev.kortex.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.kortex.core.Agent
import dev.kortex.core.graph.AgentContext
import dev.kortex.core.graph.Approver
import dev.kortex.core.graph.LlmUsageListener
import dev.kortex.core.graph.ProgressListener
import dev.kortex.core.llm.LlmProvider
import dev.kortex.core.llm.OpenAiProvider
import dev.kortex.core.log.Logger
import dev.kortex.core.state.Message
import dev.kortex.core.tool.ToolGovernor
import dev.kortex.core.tool.ToolRegistry
import dev.kortex.core.tool.builtin.defaultTools
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One line of the agent's internal reasoning (router decision, LLM call, tool call, reflection verdict). */
data class ReasoningLine(val level: Logger.Level, val tag: String, val message: String)

/** Totals for one turn, shown in the reasoning panel's footer even when collapsed. */
data class ReasoningStats(
    val tokensUsed: Int = 0,
    val toolCalls: Int = 0,
    val durationMs: Long = 0,
)

/** A rendered chat turn. [reasoning] is only populated for assistant turns that took multiple steps. */
data class ChatTurn(
    val message: Message,
    val reasoning: List<ReasoningLine> = emptyList(),
    val stats: ReasoningStats = ReasoningStats(),
)

data class ChatUi(
    val turns: List<ChatTurn> = emptyList(),
    /** Reasoning for the turn currently in flight, streamed live while [busy]. */
    val liveReasoning: List<ReasoningLine> = emptyList(),
    val liveStats: ReasoningStats = ReasoningStats(),
    val busy: Boolean = false,
    /** Live, human-readable status of the current step (e.g. "Tool usage: web_search"). */
    val status: String? = null,
    /** Set when a HIGH-risk tool is awaiting approval (pattern 13). */
    val pendingApproval: String? = null,
)

/**
 * Wires the pure-Kotlin [Agent] to Compose. The [Approver] bridges the agent's
 * Human-in-the-Loop pause to a UI dialog: the agent suspends until [resolveApproval].
 * The agent runs against OpenAI (or the stub if no key) with the default tool set.
 */
class ChatViewModel : ViewModel() {
    private val _ui = MutableStateFlow(ChatUi())
    val ui: StateFlow<ChatUi> = _ui.asStateFlow()

    private var approvalGate: CompletableDeferred<Boolean>? = null

    // OpenAI is the default provider. Falls back to the stub if no key is configured
    // in local.properties (OPENAI_API_KEY=...), so the app still runs out of the box.
    private val provider: LlmProvider =
        BuildConfig.OPENAI_API_KEY.takeIf { it.isNotBlank() }
            ?.let { OpenAiProvider(apiKey = it, logger = AndroidLogger) }
            ?: StubLlmProvider()

    private val tools = ToolRegistry(defaultTools())   // calculator, web_search, open_url, current_time
    private val approver = Approver { name, args ->
        _ui.update { it.copy(pendingApproval = "$name $args") }
        CompletableDeferred<Boolean>().also { approvalGate = it }.await()
    }
    private val progress = ProgressListener { s -> _ui.update { it.copy(status = s) } }

    fun resolveApproval(approved: Boolean) {
        _ui.update { it.copy(pendingApproval = null) }
        approvalGate?.complete(approved)
        approvalGate = null
    }

    fun send(query: String) {
        if (query.isBlank()) return
        _ui.update {
            it.copy(
                turns = it.turns + ChatTurn(Message(Message.Role.USER, query)),
                busy = true,
                status = "Thinking…",
                liveReasoning = emptyList(),
            )
        }
        viewModelScope.launch {
            // Scoped to this turn: the logger mirrors every line to Logcat (via AndroidLogger)
            // and streams it into the reasoning panel; the structured callbacks (onLlmUsage,
            // the governor's onAudit) drive the stats footer, so the numbers come from the
            // agent runtime itself rather than from parsing log strings.
            val liveLines = mutableListOf<ReasoningLine>()
            var tokens = 0
            var toolCalls = 0
            val startMs = System.currentTimeMillis()
            fun statsNow() = ReasoningStats(tokens, toolCalls, System.currentTimeMillis() - startMs)
            val turnLogger = Logger { level, tag, message, error ->
                AndroidLogger.log(level, tag, message, error)
                liveLines += ReasoningLine(level, tag, message)
                _ui.update { it.copy(liveReasoning = liveLines.toList(), liveStats = statsNow()) }
            }
            val turnCtx = AgentContext(
                llm = provider,
                tools = tools,
                governor = ToolGovernor(onAudit = {
                    // Counts every attempted call, allowed or denied. TODO Phase 3: persist to Room.
                    toolCalls++
                    _ui.update { it.copy(liveStats = statsNow()) }
                }),
                approver = approver,
                onProgress = progress,
                logger = turnLogger,
                onLlmUsage = LlmUsageListener { usage ->
                    tokens += usage.inputTokens + usage.outputTokens
                    _ui.update { it.copy(liveStats = statsNow()) }
                },
            )

            val result = Agent(turnCtx).ask(query)
            val answer = result.messages
                .lastOrNull { it.role == Message.Role.ASSISTANT && it.content.isNotBlank() }

            _ui.update { cur ->
                cur.copy(
                    // Only the final answer: reflection (pattern 4) may revise multiple times,
                    // leaving earlier drafts as non-blank ASSISTANT messages in result.messages.
                    // Its reasoning trail is everything logged across the whole run (routing,
                    // every ReAct iteration, every tool call, every reflection pass).
                    turns = cur.turns + listOfNotNull(answer?.let { ChatTurn(it, liveLines.toList(), statsNow()) }),
                    liveReasoning = emptyList(),
                    liveStats = ReasoningStats(),
                    busy = false,
                    status = null,
                )
            }
        }
    }
}
