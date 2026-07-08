package dev.kortex.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.kortex.core.mcp.McpServer
import dev.kortex.core.mcp.McpToolConnector
import dev.kortex.core.tool.ToolRegistry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
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

    private data class FlagsState(
        val showAddDialog: Boolean = false,
        val pendingDelete: String? = null,
    )

    val ui: StateFlow<McpSettingsUi> = combine(
        combine(store.disabledTools, store.customServers, _serverStatus) { a, b, c -> Triple(a, b, c) },
        combine(_serverTools, _flags, store.activeModel) { d, e, f -> Triple(d, e, f) },
        combine(store.activeProvider, store.ollamaUrl) { p, u -> p to u }
    ) { (disabled, customServers, statuses), (serverTools, flags, activeModel), (activeProvider, ollamaUrl) ->

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

        McpSettingsUi(
            builtinTools = builtins,
            servers = defaultEntries + customEntries,
            showAddDialog = flags.showAddDialog,
            pendingDelete = flags.pendingDelete,
            activeModel = activeModel,
            activeProvider = activeProvider,
            ollamaUrl = ollamaUrl,
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
     */
    private fun refreshToolEntries() {
        val allServers = mcpServers.map { it.name } +
            (_serverTools.value.keys) // include any we already know about

        // Also include names from current custom servers snapshot
        val currentStatuses = _serverStatus.value

        val grouped = mutableMapOf<String, MutableList<ToolEntry>>()
        val disabled = tools.disabledNames()

        for (tool in tools.allIncludingDisabled()) {
            if (tool.name in BUILTIN_NAMES) continue
            // Find which server this tool belongs to by prefix match
            val serverName = currentStatuses.keys.firstOrNull { srvName ->
                tool.name.startsWith(sanitize(srvName) + "_")
            }
            if (serverName != null) {
                grouped.getOrPut(serverName) { mutableListOf() }
                    .add(ToolEntry(tool.name, tool.description, tool.name !in disabled))
            }
        }

        // Update statuses for servers that connected successfully but we haven't tracked yet
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
