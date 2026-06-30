package dev.kortex.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.kortex.core.Agent
import dev.kortex.core.graph.AgentContext
import dev.kortex.core.graph.Approver
import dev.kortex.core.graph.ProgressListener
import dev.kortex.core.llm.LlmProvider
import dev.kortex.core.llm.OpenAiProvider
import dev.kortex.core.state.Message
import dev.kortex.core.tool.ToolGovernor
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
class ChatViewModel(app: Application) : AndroidViewModel(app) {
    private val container get() = getApplication<KortexApp>().container

    private val _ui = MutableStateFlow(ChatUi())
    val ui: StateFlow<ChatUi> = _ui.asStateFlow()

    private var approvalGate: CompletableDeferred<Boolean>? = null

    // OpenAI is the default provider. Falls back to the stub if no key is configured
    // in local.properties (OPENAI_API_KEY=...), so the app still runs out of the box.
    private val provider: LlmProvider =
        BuildConfig.OPENAI_API_KEY.takeIf { it.isNotBlank() }
            ?.let { OpenAiProvider(apiKey = it) }
            ?: StubLlmProvider()

    private val ctx = AgentContext(
        llm = provider,
        tools = container.toolRegistry,
        governor = ToolGovernor(onAudit = { /* TODO Phase 3: persist to Room */ }),
        approver = Approver { name, args ->
            _ui.update { it.copy(pendingApproval = "$name $args") }
            CompletableDeferred<Boolean>().also { approvalGate = it }.await()
        },
        onProgress = ProgressListener { s -> _ui.update { it.copy(status = s) } },
    )

    fun resolveApproval(approved: Boolean) {
        _ui.update { it.copy(pendingApproval = null) }
        approvalGate?.complete(approved)
        approvalGate = null
    }

    fun send(query: String) {
        if (query.isBlank()) return
        _ui.update {
            it.copy(turns = it.turns + Message(Message.Role.USER, query), busy = true, status = "Thinking…")
        }
        viewModelScope.launch {
            val result = Agent(ctx).ask(query)
            _ui.update { cur ->
                cur.copy(
                    turns = cur.turns + result.messages.filter { it.role == Message.Role.ASSISTANT && it.content.isNotBlank() },
                    trace = result.trace.map { "${it.node}:${it.kind} ${it.detail}" },
                    busy = false,
                    status = null,
                )
            }
        }
    }
}
