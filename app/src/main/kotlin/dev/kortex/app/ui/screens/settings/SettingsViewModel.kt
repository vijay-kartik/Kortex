package dev.kortex.app.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.kortex.app.domain.agent.McpConnections
import dev.kortex.app.domain.agent.McpServerInfo
import dev.kortex.app.domain.agent.ServerStatus
import dev.kortex.app.domain.security.AppLock
import dev.kortex.app.domain.security.AppLockSettings
import dev.kortex.app.domain.security.LockAfter
import dev.kortex.core.llm.EmbeddingProvider
import dev.kortex.core.llm.LlmProvider
import dev.kortex.core.tool.Tool
import javax.inject.Inject
import dev.kortex.app.data.settings.SettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// ── UI models ───────────────────────────────────────────────────────────

data class ToolEntry(
    val name: String,
    val description: String,
    val enabled: Boolean,
)

data class ServerEntry(
    val name: String,
    val url: String,
    val isDefault: Boolean,
    val tools: List<ToolEntry>,
    val status: ServerStatus,
    val hasOAuthSession: Boolean = false,
)

data class SettingsUi(
    val builtinTools: List<ToolEntry> = emptyList(),
    val servers: List<ServerEntry> = emptyList(),
    /** When true, the "Add Server" dialog is showing. */
    val showAddDialog: Boolean = false,
    /** Non-null when a deletion confirmation is pending. */
    val pendingDelete: String? = null,
    val activeModel: String = "gpt-4o",
    val supportedModels: List<String> = dev.kortex.core.llm.Models.supportedOpenAi,
    val activeProvider: String = "openai",
    val ollamaUrl: String = "http://10.0.2.2:11434/v1",
    val ollamaToken: String = "",
    /** User-entered OpenAI key; blank when the app is running on the build's local.properties key (or none). */
    val openaiApiKey: String = "",
    /** API key for Ollama Cloud (ollama.com/settings/keys); blank until the user enters one. */
    val ollamaCloudApiKey: String = "",
    val gmailAccountEmail: String? = null,
    val testEmbeddingResult: String? = null,
    val testLlmResult: String? = null,
)

// ── ViewModel ───────────────────────────────────────────────────────────

/**
 * Drives the settings sheet. Maps [McpConnections] state, [SettingsStore] preferences and
 * [AppLock] into a reactive [SettingsUi]. MCP actions (add/remove server, sign in/out) go to
 * [McpConnections]; tool toggles are written to DataStore, and AgentBootstrap's collector
 * keeps the registry in sync.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val store: SettingsStore,
    private val embedder: EmbeddingProvider,
    private val llm: LlmProvider,
    private val mcpConnections: McpConnections,
    private val appLock: AppLock,
) : ViewModel() {

    /** UI-only flags (dialog visibility etc.). */
    private val _flags = MutableStateFlow(FlagsState())

    private val _testEmbeddingResult = MutableStateFlow<String?>(null)
    private val _testLlmResult = MutableStateFlow<String?>(null)

    private data class FlagsState(
        val showAddDialog: Boolean = false,
        val pendingDelete: String? = null,
    )

    private data class ServerStateBlock(
        val disabledTools: Set<String>,
        val servers: List<McpServerInfo>,
        val statuses: Map<String, ServerStatus>,
        val oauthUrls: Set<String>
    )

    private data class ProviderPrefs(
        val provider: String,
        val ollamaUrl: String,
        val ollamaToken: String,
        val openaiApiKey: String,
        val ollamaCloudApiKey: String,
    )

    val ui: StateFlow<SettingsUi> = combine(
        combine(store.disabledTools, mcpConnections.servers, mcpConnections.statuses, mcpConnections.signedInUrls) { a, b, c, d -> ServerStateBlock(a, b, c, d) },
        combine(mcpConnections.toolsByServer, _flags, store.activeModel) { d, e, f -> Triple(d, e, f) },
        combine(
            combine(store.activeProvider, store.ollamaUrl, store.ollamaToken, store.openaiApiKey) { p, u, t, k -> listOf(p, u, t ?: "", k ?: "") },
            store.ollamaCloudApiKey
        ) { l1, ollamaCloud ->
            ProviderPrefs(
                provider = l1[0], ollamaUrl = l1[1], ollamaToken = l1[2], openaiApiKey = l1[3],
                ollamaCloudApiKey = ollamaCloud ?: ""
            )
        },
        combine(store.gmailAccountEmail, _testEmbeddingResult, _testLlmResult) { gmail, testRes, llmRes -> listOf(gmail, testRes, llmRes) },
    ) { (disabled, servers, statuses, oauthUrls), (serverTools, flags, activeModel), prefs, fourthBlock ->
        val gmailAccountEmail = fourthBlock[0] as String?
        val testEmbeddingResult = fourthBlock[1] as String?
        val testLlmResult = fourthBlock[2] as String?

        fun Tool.toEntry() = ToolEntry(name, description, enabled = name !in disabled)

        // Built-in tools: every registered tool no MCP server owns.
        val builtins = mcpConnections.builtinTools().map { it.toEntry() }

        // MCP servers (default + custom) — merge connection status + discovered tools
        val serverEntries = servers.map { srv ->
            ServerEntry(
                name = srv.name,
                url = srv.url,
                isDefault = srv.isDefault,
                tools = serverTools[srv.name]?.map { it.toEntry() } ?: emptyList(),
                status = statuses[srv.name] ?: ServerStatus.CONNECTING,
                hasOAuthSession = srv.url in oauthUrls,
            )
        }

        SettingsUi(
            builtinTools = builtins,
            servers = serverEntries,
            showAddDialog = flags.showAddDialog,
            pendingDelete = flags.pendingDelete,
            activeModel = activeModel,
            activeProvider = prefs.provider,
            ollamaUrl = prefs.ollamaUrl,
            ollamaToken = prefs.ollamaToken,
            openaiApiKey = prefs.openaiApiKey,
            ollamaCloudApiKey = prefs.ollamaCloudApiKey,
            gmailAccountEmail = gmailAccountEmail,
            testEmbeddingResult = testEmbeddingResult,
            testLlmResult = testLlmResult,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUi())

    // ── public actions ──────────────────────────────────────────────────

    val appLockSettings: StateFlow<AppLockSettings> = appLock.settings

    /** Only after a successful biometric check in the screen. */
    fun setAppLockEnabled(enabled: Boolean) = appLock.setEnabled(enabled)

    fun setLockAfter(lockAfter: LockAfter) = appLock.setLockAfter(lockAfter)

    fun setHideInRecents(hide: Boolean) = appLock.setHideInRecents(hide)

    /** Keeps the PIN screen (older Android) from counting as leaving the app mid-check. */
    fun setAuthInProgress(inProgress: Boolean) {
        appLock.authInProgress = inProgress
    }

    fun toggleTool(toolName: String, enabled: Boolean) {
        viewModelScope.launch {
            store.setToolDisabled(toolName, disabled = !enabled)
        }
    }

    fun toggleServer(serverName: String, enabled: Boolean) {
        viewModelScope.launch {
            val toolsToToggle = mcpConnections.toolsByServer.value[serverName]?.map { it.name } ?: emptyList()
            if (toolsToToggle.isNotEmpty()) {
                store.setServerToolsDisabled(toolsToToggle, disabled = !enabled)
            }
        }
    }

    fun showAddDialog() { _flags.update { it.copy(showAddDialog = true) } }
    fun dismissAddDialog() { _flags.update { it.copy(showAddDialog = false) } }

    fun setActiveModel(model: String) {
        viewModelScope.launch {
            store.setActiveModel(model)
        }
    }

    fun setActiveProvider(provider: String) {
        viewModelScope.launch {
            store.setActiveProvider(provider)
        }
    }

    fun setOllamaUrl(url: String) {
        viewModelScope.launch {
            store.setOllamaUrl(url)
        }
    }

    fun setOllamaToken(token: String) {
        viewModelScope.launch {
            store.setOllamaToken(token)
        }
    }

    fun setOpenaiApiKey(key: String) {
        viewModelScope.launch {
            store.setOpenaiApiKey(key.trim())
        }
    }

    fun setOllamaCloudApiKey(key: String) {
        viewModelScope.launch {
            store.setOllamaCloudApiKey(key.trim())
        }
    }

    fun testEmbeddingConnection() {
        viewModelScope.launch {
            _testEmbeddingResult.value = "Testing..."
            try {
                val result = embedder.embed("test connection")
                _testEmbeddingResult.value = "Success! Dimension: ${result.size}"
            } catch (e: Exception) {
                _testEmbeddingResult.value = "Failed: ${e.message}"
            }
        }
    }

    fun clearTestEmbeddingResult() {
        _testEmbeddingResult.value = null
    }

    fun testLlmConnection() {
        viewModelScope.launch {
            _testLlmResult.value = "Testing LLM..."
            try {
                val req = dev.kortex.core.llm.LlmRequest(
                    model = store.activeModel.first(),
                    messages = listOf(dev.kortex.core.state.Message(dev.kortex.core.state.Message.Role.USER, "Respond with a single word: OK")),
                    maxTokens = 10
                )
                val resp = llm.complete(req)
                _testLlmResult.value = "Success! Response: ${resp.message.content}"
            } catch (e: Exception) {
                _testLlmResult.value = "Failed: ${e.message}"
            }
        }
    }

    fun clearTestLlmResult() {
        _testLlmResult.value = null
    }

    fun setGmailAccountEmail(email: String?) {
        viewModelScope.launch {
            store.setGmailAccountEmail(email?.trim())
        }
    }

    fun addServer(name: String, url: String, bearerToken: String?) {
        _flags.update { it.copy(showAddDialog = false) }
        if (name.isBlank() || url.isBlank()) return

        viewModelScope.launch {
            mcpConnections.add(name.trim(), url.trim(), bearerToken?.trim()?.ifBlank { null })
        }
    }

    fun requestDelete(serverName: String) { _flags.update { it.copy(pendingDelete = serverName) } }
    fun cancelDelete() { _flags.update { it.copy(pendingDelete = null) } }

    fun confirmDelete(serverName: String) {
        _flags.update { it.copy(pendingDelete = null) }
        viewModelScope.launch { mcpConnections.remove(serverName) }
    }

    fun signIn(serverName: String) {
        viewModelScope.launch { mcpConnections.signIn(serverName) }
    }

    fun signOut(serverName: String) {
        viewModelScope.launch { mcpConnections.signOut(serverName) }
    }
}
