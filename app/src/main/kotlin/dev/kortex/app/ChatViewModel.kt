package dev.kortex.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.kortex.core.Agent
import dev.kortex.core.graph.AgentContext
import dev.kortex.core.graph.Approver
import dev.kortex.core.graph.LlmUsageListener
import dev.kortex.core.graph.ProgressListener
import dev.kortex.core.llm.LlmProvider
import dev.kortex.core.log.Logger
import dev.kortex.core.mcp.McpServer
import dev.kortex.core.mcp.McpToolConnector
import dev.kortex.core.state.Message
import dev.kortex.core.tool.ToolGovernor
import dev.kortex.core.tool.ToolRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import dev.kortex.app.store.ChatSessionEntity
import java.util.UUID

/** One line of the agent's internal reasoning (router decision, LLM call, tool call, reflection verdict). */
@Serializable
data class ReasoningLine(val level: Logger.Level, val tag: String, val message: String)

/** Totals for one turn, shown in the reasoning panel's footer even when collapsed. */
@Serializable
data class ReasoningStats(
    val tokensUsed: Int = 0,
    val toolCalls: Int = 0,
    val durationMs: Long = 0,
)

/** A rendered chat turn. [reasoning] is only populated for assistant turns that took multiple steps. */
@Serializable
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
    val activeProvider: String? = null,
    val activeModel: String? = null,
)

/**
 * Wires the pure-Kotlin [Agent] to Compose. The [Approver] bridges the agent's
 * Human-in-the-Loop pause to a UI dialog: the agent suspends until [resolveApproval].
 * The shared [ToolRegistry] from [KortexContainer] is used so that the MCP settings
 * screen and this ViewModel operate on the same tool set.
 */
class ChatViewModel(application: Application) : AndroidViewModel(application) {
    private val container = (application as KortexApp).container

    private val _ui = MutableStateFlow(ChatUi())
    val ui: StateFlow<ChatUi> = _ui.asStateFlow()

    private val _stagedAttachments = MutableStateFlow<List<dev.kortex.core.state.Attachment>>(emptyList())
    val stagedAttachments: StateFlow<List<dev.kortex.core.state.Attachment>> = _stagedAttachments.asStateFlow()

    private var approvalGate: CompletableDeferred<Boolean>? = null

    private val provider: LlmProvider = container.llm
    private val tools: ToolRegistry = container.toolRegistry
    private val mcpStore: McpStore = container.mcpStore
    private val sessionDao = container.chatSessionDao

    val sessions = sessionDao.getAll()
    private var currentSessionId: String = UUID.randomUUID().toString()
    private val json = Json { ignoreUnknownKeys = true }

    private val approver = Approver { name, args ->
        _ui.update { it.copy(pendingApproval = "$name $args") }
        CompletableDeferred<Boolean>().also { approvalGate = it }.await()
    }
    private val progress = ProgressListener { s -> _ui.update { it.copy(status = s) } }

    init {
        // Apply persisted disabled-tool set, then connect MCP servers (default + custom).
        viewModelScope.launch {
            val disabled = mcpStore.disabledTools.first()
            tools.setDisabled(disabled)
        }
        viewModelScope.launch {
            connectMcpServers()
        }
        // Keep the registry in sync whenever the user toggles tools from the settings sheet.
        viewModelScope.launch {
            mcpStore.disabledTools.collect { disabled -> tools.setDisabled(disabled) }
        }
        // Update the active reasoning model globally whenever it changes.
        viewModelScope.launch {
            mcpStore.activeModel.collect { model -> dev.kortex.core.llm.Models.REASONING = model }
        }
    }

    /**
     * Connects the hardcoded default MCP servers plus any user-added custom servers.
     * Called once at init; new custom servers added mid-session are connected by
     * [McpSettingsViewModel] directly.
     */
    private suspend fun connectMcpServers() {
        val allServers = mcpServers + mcpStore.customServers.first().map {
            McpServer(name = it.name, url = it.url, bearerToken = it.bearerToken)
        }
        if (allServers.isNotEmpty()) {
            McpToolConnector(tools, AndroidLogger).connectAll(allServers)
        }
    }

    fun resolveApproval(approved: Boolean) {
        _ui.update { it.copy(pendingApproval = null) }
        approvalGate?.complete(approved)
        approvalGate = null
    }

    fun stageAttachment(attachment: dev.kortex.core.state.Attachment) {
        _stagedAttachments.update { it + attachment }
    }

    fun removeStagedAttachment(index: Int) {
        _stagedAttachments.update { it.filterIndexed { i, _ -> i != index } }
    }

    fun loadSession(sessionId: String) {
        viewModelScope.launch {
            val session = sessionDao.getById(sessionId)
            if (session != null) {
                currentSessionId = session.id
                val loadedTurns = json.decodeFromString<List<ChatTurn>>(session.turnsJson)
                _ui.update { it.copy(turns = loadedTurns, busy = false, status = null) }
            }
        }
    }

    fun startNewSession() {
        currentSessionId = UUID.randomUUID().toString()
        _ui.update { it.copy(turns = emptyList(), busy = false, status = null) }
    }

    fun send(query: String) {
        val attachmentsToSend = _stagedAttachments.value
        _stagedAttachments.value = emptyList()

        if (query.isBlank() && attachmentsToSend.isEmpty()) return
        viewModelScope.launch {
            val activeProviderName = mcpStore.activeProvider.first()
            val model = when (activeProviderName) {
                "ollama" -> mcpStore.activeModel.first()
                else -> "gpt-4o"
            }
            _ui.update {
                it.copy(
                    turns = it.turns + ChatTurn(Message(Message.Role.USER, query, attachmentsToSend)),
                    busy = true,
                    status = "Thinking…",
                    liveReasoning = emptyList(),
                    activeProvider = activeProviderName,
                    activeModel = model ?: "unknown model",
                )
            }
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

            val historyMessages = _ui.value.turns.map { it.message }
            val result = Agent(turnCtx).ask(query, attachmentsToSend, history = historyMessages)
            val answer = result.messages
                .lastOrNull { it.role == Message.Role.ASSISTANT && it.content.isNotBlank() }

            _ui.update { cur ->
                val newTurns = cur.turns + listOfNotNull(answer?.let { ChatTurn(it, liveLines.toList(), statsNow()) })
                viewModelScope.launch {
                    val title = if (newTurns.size <= 2) query.take(40) else sessionDao.getById(currentSessionId)?.title ?: query.take(40)
                    sessionDao.upsert(
                        ChatSessionEntity(
                            id = currentSessionId,
                            title = title,
                            turnsJson = json.encodeToString(newTurns),
                            updatedAtMillis = System.currentTimeMillis()
                        )
                    )
                }
                cur.copy(
                    turns = newTurns,
                    liveReasoning = emptyList(),
                    liveStats = ReasoningStats(),
                    busy = false,
                    status = null,
                )
            }
        }
    }
}
