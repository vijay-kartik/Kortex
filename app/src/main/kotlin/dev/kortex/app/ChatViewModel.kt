package dev.kortex.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.kortex.core.Agent
import dev.kortex.core.graph.AgentContext
import dev.kortex.core.graph.Approver
import dev.kortex.core.state.Message
import dev.kortex.core.tool.RiskLevel
import dev.kortex.core.tool.ToolGovernor
import dev.kortex.core.tool.ToolRegistry
import dev.kortex.core.tool.ToolResult
import dev.kortex.core.tool.tool
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ChatUi(
    val turns: List<Message> = emptyList(),
    val trace: List<String> = emptyList(),
    val busy: Boolean = false,
    /** Set when a HIGH-risk tool is awaiting approval (pattern 13). */
    val pendingApproval: String? = null,
)

/**
 * Wires the pure-Kotlin [Agent] to Compose. The [Approver] bridges the agent's
 * Human-in-the-Loop pause to a UI dialog: the agent suspends until [resolveApproval].
 *
 * NOTE: uses a fake/echo provider until the Claude Ktor client lands in Phase 1.
 */
class ChatViewModel : ViewModel() {
    private val _ui = MutableStateFlow(ChatUi())
    val ui: StateFlow<ChatUi> = _ui.asStateFlow()

    private var approvalGate: CompletableDeferred<Boolean>? = null

    private val sampleTool = tool("device_time", "Get the current device time") {
        risk(RiskLevel.LOW)
        execute { ToolResult(true, java.time.LocalDateTime.now().toString()) }
    }

    private val ctx = AgentContext(
        llm = StubLlmProvider(),                       // TODO Phase 1: ClaudeProvider
        tools = ToolRegistry(listOf(sampleTool)),
        governor = ToolGovernor(onAudit = { /* TODO Phase 3: persist to Room */ }),
        approver = Approver { name, args ->
            _ui.update { it.copy(pendingApproval = "$name $args") }
            CompletableDeferred<Boolean>().also { approvalGate = it }.await()
        },
    )

    fun resolveApproval(approved: Boolean) {
        _ui.update { it.copy(pendingApproval = null) }
        approvalGate?.complete(approved)
        approvalGate = null
    }

    fun send(query: String) {
        if (query.isBlank()) return
        _ui.update {
            it.copy(turns = it.turns + Message(Message.Role.USER, query), busy = true)
        }
        viewModelScope.launch {
            val result = Agent(ctx).ask(query)
            _ui.update { cur ->
                cur.copy(
                    turns = cur.turns + result.messages.filter { it.role == Message.Role.ASSISTANT && it.content.isNotBlank() },
                    trace = result.trace.map { "${it.node}:${it.kind} ${it.detail}" },
                    busy = false,
                )
            }
        }
    }
}
