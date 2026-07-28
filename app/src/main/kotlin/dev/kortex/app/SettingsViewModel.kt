package dev.kortex.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.kortex.core.log.w
import dev.kortex.core.mcp.McpServer
import dev.kortex.core.mcp.McpToolConnector
import dev.kortex.core.tool.ToolRegistry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

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

enum class ServerStatus { CONNECTING, CONNECTED, ERROR, NEEDS_AUTH }

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

// ── Names of the four builtins, so we can partition them in the UI ──────

private val BUILTIN_NAMES = setOf(
    "calculator", 
    "web_search", 
    "open_url", 
    "current_time", 
    "create_reminder", 
    "create_calendar_event",
    "gmail_search",
    "save_knowledge",
    "memory_search",
    "format_itinerary",
    "whatsapp_send_message"
)

// ── ViewModel ───────────────────────────────────────────────────────────

/**
 * Drives the MCP-settings sheet. Reads the shared [ToolRegistry] and [McpStore] from
 * [KortexContainer] and exposes a reactive [SettingsUi]. Mutations (add/remove server,
 * toggle tool) are written to DataStore, and the [ChatViewModel]'s collector keeps the
 * registry in sync.
 */
class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val container = (application as KortexApp).container
    private val tools: ToolRegistry = container.toolRegistry
    private val store: SettingsStore = container.settingsStore
    private val embedder = container.embedder

    /**
     * Per-server connection status, keyed by server name. Starts empty; updated as servers
     * are connected at init and when the user adds a new one.
     */
    private val _serverStatus = MutableStateFlow<Map<String, ServerStatus>>(emptyMap())

    /** Discovered tools per MCP server, keyed by server name. */
    private val _serverTools = MutableStateFlow<Map<String, List<ToolEntry>>>(emptyMap())

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
        val customServers: List<CustomMcpServer>,
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
        combine(store.disabledTools, store.customServers, _serverStatus, store.oauthStates) { a, b, c, d -> ServerStateBlock(a, b, c, d.keys) },
        combine(_serverTools, _flags, store.activeModel) { d, e, f -> Triple(d, e, f) },
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
    ) { (disabled, customServers, statuses, oauthUrls), (serverTools, flags, activeModel), prefs, fourthBlock ->
        val gmailAccountEmail = fourthBlock[0] as String?
        val testEmbeddingResult = fourthBlock[1] as String?
        val testLlmResult = fourthBlock[2] as String?

        // Built-in tools
        val builtins = tools.allIncludingDisabled()
            .filter { it.name in BUILTIN_NAMES }
            .map { ToolEntry(it.name, it.description, it.name !in disabled) }

        // MCP servers (default + custom) — merge connection status + discovered tools
        val defaultEntries = mcpServers.map { srv ->
            ServerEntry(
                name = srv.name,
                url = srv.url,
                isDefault = true,
                tools = serverTools[srv.name]?.map { it.copy(enabled = it.name !in disabled) } ?: emptyList(),
                status = statuses[srv.name] ?: ServerStatus.CONNECTING,
                hasOAuthSession = srv.url in oauthUrls,
            )
        }
        val customEntries = customServers.map { srv ->
            ServerEntry(
                name = srv.name,
                url = srv.url,
                isDefault = false,
                tools = serverTools[srv.name]?.map { it.copy(enabled = it.name !in disabled) } ?: emptyList(),
                status = statuses[srv.name] ?: ServerStatus.CONNECTING,
                hasOAuthSession = srv.url in oauthUrls,
            )
        }

        SettingsUi(
            builtinTools = builtins,
            servers = defaultEntries + customEntries,
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

    init {
        // Populate the tool entries for servers that ChatViewModel already connected.
        // We walk the registry and partition tools by their server-name prefix.
        viewModelScope.launch {
            // Give the ChatViewModel a moment to finish connecting (it races in parallel).
            // A more robust approach would be an event bus, but this is sufficient: the
            // combine re-fires whenever _serverTools changes, so late arrivals show up.
            kotlinx.coroutines.delay(1_500)
            refreshToolEntries()
        }
        
        // Reconnect servers when their OAuth state changes (e.g. after a sign-in callback)
        viewModelScope.launch {
            var previousStates = store.oauthStates.first()
            store.oauthStates.collect { currentStates ->
                for ((url, state) in currentStates) {
                    val prev = previousStates[url]
                    if (state != null && (prev == null || prev.accessToken != state.accessToken)) {
                        val customServers = store.customServers.first()
                        val serverModel = customServers.find { it.url == url }
                        if (serverModel != null) {
                            val mcpServer = dev.kortex.core.mcp.McpServer(
                                name = serverModel.name,
                                url = serverModel.url,
                                bearerToken = serverModel.bearerToken,
                                tokenProvider = container.mcpOAuthManager.tokenProviderFor(url)
                            )
                            connectServer(mcpServer)
                        }
                    }
                }
                previousStates = currentStates
            }
        }
        
        // Track servers that failed auth on startup
        viewModelScope.launch {
            container.mcpAuthFailures.collect { failures ->
                if (failures.isNotEmpty()) {
                    val updates = failures.associateWith { ServerStatus.NEEDS_AUTH }
                    _serverStatus.update { it + updates }
                }
            }
        }
    }

    // ── public actions ──────────────────────────────────────────────────

    fun toggleTool(toolName: String, enabled: Boolean) {
        viewModelScope.launch {
            store.setToolDisabled(toolName, disabled = !enabled)
        }
    }

    fun toggleServer(serverName: String, enabled: Boolean) {
        viewModelScope.launch {
            val toolsToToggle = _serverTools.value[serverName]?.map { it.name } ?: emptyList()
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
                val resp = container.llm.complete(req)
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

        val server = CustomMcpServer(name = name.trim(), url = url.trim(), bearerToken = bearerToken?.trim()?.ifBlank { null })
        viewModelScope.launch {
            store.addServer(server)
            connectServer(
                McpServer(
                    name = server.name,
                    url = server.url,
                    bearerToken = server.bearerToken,
                    tokenProvider = container.mcpOAuthManager.tokenProviderFor(server.url)
                )
            )
        }
    }

    fun requestDelete(serverName: String) { _flags.update { it.copy(pendingDelete = serverName) } }
    fun cancelDelete() { _flags.update { it.copy(pendingDelete = null) } }

    fun confirmDelete(serverName: String) {
        _flags.update { it.copy(pendingDelete = null) }
        viewModelScope.launch {
            // Unregister all tools belonging to this server from the shared registry.
            val prefix = sanitize(serverName) + "_"
            tools.allIncludingDisabled()
                .filter { it.name.startsWith(prefix) }
                .forEach { tools.unregister(it.name) }

            _serverStatus.update { it - serverName }
            _serverTools.update { it - serverName }
            container.mcpAuthFailures.update { it - serverName }
            store.removeServer(serverName)
        }
    }

    fun signIn(serverName: String) {
        viewModelScope.launch {
            val customServers = store.customServers.first()
            val serverModel = customServers.find { it.name == serverName } ?: return@launch
            val mcpServer = dev.kortex.core.mcp.McpServer(
                name = serverModel.name,
                url = serverModel.url,
                bearerToken = serverModel.bearerToken,
                tokenProvider = container.mcpOAuthManager.tokenProviderFor(serverModel.url)
            )
            container.mcpOAuthManager.beginSignIn(mcpServer)
        }
    }

    fun signOut(serverName: String) {
        viewModelScope.launch {
            val customServers = store.customServers.first()
            val serverModelUrl = customServers.find { it.name == serverName }?.url
                ?: mcpServers.find { it.name == serverName }?.url
                ?: return@launch
            
            store.setOauthState(serverModelUrl, null)
            
            val prefix = sanitize(serverName) + "_"
            tools.allIncludingDisabled()
                .filter { it.name.startsWith(prefix) }
                .forEach { tools.unregister(it.name) }
                
            _serverStatus.update { it + (serverName to ServerStatus.NEEDS_AUTH) }
            _serverTools.update { it - serverName }
        }
    }

    // ── internals ───────────────────────────────────────────────────────

    private suspend fun connectServer(server: McpServer) {
        _serverStatus.update { it + (server.name to ServerStatus.CONNECTING) }
        try {
            val count = McpToolConnector(tools, AndroidLogger).connect(server)
            _serverStatus.update {
                it + (server.name to if (count > 0) ServerStatus.CONNECTED else ServerStatus.ERROR)
            }
            if (count > 0) {
                container.mcpAuthFailures.update { it - server.name }
            }
        } catch (e: dev.kortex.core.mcp.McpUnauthorizedException) {
            _serverStatus.update { it + (server.name to ServerStatus.NEEDS_AUTH) }
            container.mcpAuthFailures.update { it + server.name }
        } catch (e: Exception) {
            _serverStatus.update { it + (server.name to ServerStatus.ERROR) }
        }
        refreshToolEntries()
    }

    /**
     * Scans the shared [ToolRegistry] and groups non-builtin tools by their server-name
     * prefix (the `server_tool` naming convention from [McpToolConnector]).
     *
     * Matches against the *actual* set of known server names (defaults + persisted custom
     * servers + Composio, if configured) rather than servers already tracked in
     * [_serverStatus] — that map only gets entries from [connectServer] calls made by this
     * ViewModel, so a server connected by [ChatViewModel]'s separate startup pass (the
     * normal case for anything saved from a prior session) would otherwise never be
     * discoverable here and would sit on "Connecting…" forever despite being live.
     */
    private suspend fun refreshToolEntries() {
        val knownServerNames = mcpServers.map { it.name } +
            store.customServers.first().map { it.name }

        val grouped = mutableMapOf<String, MutableList<ToolEntry>>()
        val disabled = tools.disabledNames()

        for (tool in tools.allIncludingDisabled()) {
            if (tool.name in BUILTIN_NAMES) continue
            // Find which server this tool belongs to by prefix match
            val serverName = knownServerNames.firstOrNull { srvName ->
                tool.name.startsWith(sanitize(srvName) + "_")
            }
            if (serverName != null) {
                grouped.getOrPut(serverName) { mutableListOf() }
                    .add(ToolEntry(tool.name, tool.description, tool.name !in disabled))
            }
        }

        // Update statuses for servers that connected successfully but we haven't tracked yet
        val currentStatuses = _serverStatus.value
        val newStatuses = mutableMapOf<String, ServerStatus>()
        for (srvName in grouped.keys) {
            if (srvName !in currentStatuses) {
                newStatuses[srvName] = ServerStatus.CONNECTED
            }
        }
        if (newStatuses.isNotEmpty()) {
            _serverStatus.update { it + newStatuses }
        }

        _serverTools.update { grouped }
    }

    private fun sanitize(s: String) = s.replace(Regex("[^A-Za-z0-9_-]"), "_")
}
