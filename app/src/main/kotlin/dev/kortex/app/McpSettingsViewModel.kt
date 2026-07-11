package dev.kortex.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.kortex.core.log.w
import dev.kortex.core.mcp.McpServer
import dev.kortex.core.mcp.McpToolConnector
import dev.kortex.core.mcp.resolveComposioServer
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
)

enum class ServerStatus { CONNECTING, CONNECTED, ERROR }

data class McpSettingsUi(
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
    val composioApiKey: String = "",
    val composioUserId: String = "",
    /** Null until the first connect attempt (this session or a prior one) resolves. */
    val composioStatus: ServerStatus? = null,
    /** Set on a failed connect attempt; cleared as soon as a new attempt starts. */
    val composioError: String? = null,
    /** True while the user has deliberately reopened the form on an already-connected setup. */
    val composioEditing: Boolean = false,
    val composioToolCount: Int = 0,
    val gmailAccountEmail: String? = null,
)

// ── Names of the four builtins, so we can partition them in the UI ──────

private val BUILTIN_NAMES = setOf("calculator", "web_search", "open_url", "current_time")

// ── ViewModel ───────────────────────────────────────────────────────────

/**
 * Drives the MCP-settings sheet. Reads the shared [ToolRegistry] and [McpStore] from
 * [KortexContainer] and exposes a reactive [McpSettingsUi]. Mutations (add/remove server,
 * toggle tool) are written to DataStore, and the [ChatViewModel]'s collector keeps the
 * registry in sync.
 */
class McpSettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val container = (application as KortexApp).container
    private val tools: ToolRegistry = container.toolRegistry
    private val store: McpStore = container.mcpStore

    /**
     * Per-server connection status, keyed by server name. Starts empty; updated as servers
     * are connected at init and when the user adds a new one.
     */
    private val _serverStatus = MutableStateFlow<Map<String, ServerStatus>>(emptyMap())

    /** Discovered tools per MCP server, keyed by server name. */
    private val _serverTools = MutableStateFlow<Map<String, List<ToolEntry>>>(emptyMap())

    /** UI-only flags (dialog visibility etc.). */
    private val _flags = MutableStateFlow(FlagsState())

    /** Set on a failed Composio connect attempt; cleared as soon as a new attempt starts. */
    private val _composioError = MutableStateFlow<String?>(null)

    /** True while the user has reopened the credentials form on an already-connected setup. */
    private val _composioEditing = MutableStateFlow(false)

    private data class FlagsState(
        val showAddDialog: Boolean = false,
        val pendingDelete: String? = null,
    )

    private data class ProviderPrefs(
        val provider: String,
        val ollamaUrl: String,
        val ollamaToken: String,
        val openaiApiKey: String,
        val ollamaCloudApiKey: String,
    )

    val ui: StateFlow<McpSettingsUi> = combine(
        combine(store.disabledTools, store.customServers, _serverStatus) { a, b, c -> Triple(a, b, c) },
        combine(_serverTools, _flags, store.activeModel) { d, e, f -> Triple(d, e, f) },
        combine(store.activeProvider, store.ollamaUrl, store.ollamaToken, store.openaiApiKey, store.ollamaCloudApiKey) { p, u, t, k, oc -> ProviderPrefs(p, u, t ?: "", k ?: "", oc ?: "") },
        combine(store.composioApiKey, store.composioUserId) { k, u -> k to u },
        combine(_composioError, _composioEditing, store.gmailAccountEmail) { err, editing, gmail -> Triple(err, editing, gmail) },
    ) { (disabled, customServers, statuses), (serverTools, flags, activeModel), (activeProvider, ollamaUrl, ollamaToken, openaiApiKey, ollamaCloudApiKey), (composioApiKey, composioUserId), (composioError, composioEditing, gmailAccountEmail) ->

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
            )
        }
        val customEntries = customServers.map { srv ->
            ServerEntry(
                name = srv.name,
                url = srv.url,
                isDefault = false,
                tools = serverTools[srv.name]?.map { it.copy(enabled = it.name !in disabled) } ?: emptyList(),
                status = statuses[srv.name] ?: ServerStatus.CONNECTING,
            )
        }
        // Composio (Gmail) shows up once an API key is configured, even before
        // the first successful connect — the card then reflects CONNECTING/ERROR/CONNECTED.
        val composioStatus = statuses[COMPOSIO_GMAIL_SERVER_NAME]
        val composioEntries = if (!composioApiKey.isNullOrBlank()) {
            listOf(
                ServerEntry(
                    name = COMPOSIO_GMAIL_SERVER_NAME,
                    url = "Composio Tool Router — Gmail",
                    isDefault = false,
                    tools = serverTools[COMPOSIO_GMAIL_SERVER_NAME]?.map { it.copy(enabled = it.name !in disabled) } ?: emptyList(),
                    status = composioStatus ?: ServerStatus.CONNECTING,
                )
            )
        } else emptyList()

        McpSettingsUi(
            builtinTools = builtins,
            servers = defaultEntries + customEntries + composioEntries,
            showAddDialog = flags.showAddDialog,
            pendingDelete = flags.pendingDelete,
            activeModel = activeModel,
            activeProvider = activeProvider,
            ollamaUrl = ollamaUrl,
            ollamaToken = ollamaToken,
            openaiApiKey = openaiApiKey,
            ollamaCloudApiKey = ollamaCloudApiKey,
            composioApiKey = composioApiKey ?: "",
            composioUserId = composioUserId ?: "",
            composioStatus = composioStatus,
            composioError = composioError,
            composioEditing = composioEditing,
            composioToolCount = serverTools[COMPOSIO_GMAIL_SERVER_NAME]?.size ?: 0,
            gmailAccountEmail = gmailAccountEmail,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), McpSettingsUi())

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
    }

    // ── public actions ──────────────────────────────────────────────────

    fun toggleTool(toolName: String, enabled: Boolean) {
        viewModelScope.launch {
            store.setToolDisabled(toolName, disabled = !enabled)
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

    fun setComposioApiKey(key: String) {
        viewModelScope.launch {
            store.setComposioApiKey(key.trim())
        }
    }

    fun setComposioUserId(userId: String) {
        viewModelScope.launch {
            store.setComposioUserId(userId.trim())
        }
    }

    fun setGmailAccountEmail(email: String?) {
        viewModelScope.launch {
            store.setGmailAccountEmail(email?.trim())
        }
    }

    /** Reopens the credentials form on an already-connected Composio setup. */
    fun editComposio() {
        _composioError.value = null
        _composioEditing.value = true
    }

    /** Backs out of the credentials form without attempting a connect. */
    fun cancelComposioEdit() {
        _composioError.value = null
        _composioEditing.value = false
    }

    /** Resolves the Composio MCP server and (re)connects it — call after editing
     *  the API key, since [ChatViewModel] only resolves one at startup. */
    fun reconnectComposio() {
        viewModelScope.launch {
            val apiKey = store.composioApiKey.first()?.trim().orEmpty()
            if (apiKey.isBlank()) return@launch

            _composioError.value = null
            _serverStatus.update { it + (COMPOSIO_GMAIL_SERVER_NAME to ServerStatus.CONNECTING) }

            val server = runCatching {
                resolveComposioServer(
                    apiKey = apiKey,
                    serverName = COMPOSIO_GMAIL_SERVER_NAME,
                )
            }.getOrElse { err ->
                AndroidLogger.w("Composio", "session create failed: ${err.message}", err)
                _composioError.value = composioErrorMessage(err)
                _serverStatus.update { it + (COMPOSIO_GMAIL_SERVER_NAME to ServerStatus.ERROR) }
                return@launch
            }

            connectServer(server)
            // Composio Gmail tools are opt-in: newly discovered ones start disabled.
            store.defaultDisableNewTools(composioGmailToolNames(tools))
            if (_serverStatus.value[COMPOSIO_GMAIL_SERVER_NAME] == ServerStatus.ERROR) {
                _composioError.value = "Connected, but no tools came back. Make sure the user_id above " +
                    "matches the one your Gmail connection was authorized under in Composio."
            } else {
                _composioEditing.value = false
            }
        }
    }

    /** Composio's error bodies are `{"error":{"message": "..."}}`; pull just the message out
     *  of the exception text so the settings screen shows a sentence, not a raw JSON blob. */
    private fun composioErrorMessage(err: Throwable): String {
        val raw = err.message ?: return "Couldn't reach Composio. Check your connection and try again."
        val jsonStart = raw.indexOf('{')
        if (jsonStart == -1) return raw
        return runCatching {
            Json.parseToJsonElement(raw.substring(jsonStart)).jsonObject["error"]
                ?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
        }.getOrNull() ?: raw
    }

    fun addServer(name: String, url: String, bearerToken: String?) {
        _flags.update { it.copy(showAddDialog = false) }
        if (name.isBlank() || url.isBlank()) return

        val server = CustomMcpServer(name = name.trim(), url = url.trim(), bearerToken = bearerToken?.trim()?.ifBlank { null })
        viewModelScope.launch {
            store.addServer(server)
            connectServer(McpServer(name = server.name, url = server.url, bearerToken = server.bearerToken))
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
            store.removeServer(serverName)
        }
    }

    // ── internals ───────────────────────────────────────────────────────

    private suspend fun connectServer(server: McpServer) {
        _serverStatus.update { it + (server.name to ServerStatus.CONNECTING) }
        val count = McpToolConnector(tools, AndroidLogger).connect(server)
        _serverStatus.update {
            it + (server.name to if (count > 0) ServerStatus.CONNECTED else ServerStatus.ERROR)
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
        val composioConfigured = !store.composioApiKey.first().isNullOrBlank()
        val knownServerNames = mcpServers.map { it.name } +
            store.customServers.first().map { it.name } +
            listOfNotNull(COMPOSIO_GMAIL_SERVER_NAME.takeIf { composioConfigured })

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
