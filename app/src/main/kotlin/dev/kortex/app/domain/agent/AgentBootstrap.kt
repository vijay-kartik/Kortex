package dev.kortex.app.domain.agent

import dev.kortex.app.data.auth.McpOAuthManager
import dev.kortex.app.data.settings.SettingsStore
import dev.kortex.core.llm.Models
import dev.kortex.core.log.AndroidLogger
import dev.kortex.core.log.w
import dev.kortex.core.mcp.McpServer
import dev.kortex.core.mcp.McpToolConnector
import dev.kortex.core.mcp.McpUnauthorizedException
import dev.kortex.core.mcp.mcpServers
import dev.kortex.core.tool.ToolRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * App-wide agent setup that every agent run depends on, whichever screen (or none) is open:
 * applies the user's disabled tools, connects MCP servers into the shared [ToolRegistry], and
 * keeps the global [Models] in line with the selected provider. Started once from KortexApp.
 *
 * Runs on the main thread: [ToolRegistry] isn't thread-safe, and SettingsViewModel mutates
 * it from the main thread too.
 */
class AgentBootstrap(
    private val scope: CoroutineScope,
    private val tools: ToolRegistry,
    private val settingsStore: SettingsStore,
    private val mcpOAuthManager: McpOAuthManager,
    private val mcpAuthFailures: MutableStateFlow<Set<String>>,
) {
    private var started = false

    fun start() {
        if (started) return
        started = true

        // Keep the registry in sync with the disabled-tool set, including toggles from settings.
        scope.launch(Dispatchers.Main.immediate) {
            settingsStore.disabledTools.collect { disabled -> tools.setDisabled(disabled) }
        }
        scope.launch(Dispatchers.Main.immediate) {
            connectMcpServers()
        }
        // Update the active reasoning/routing models globally whenever they change. Ollama
        // hosts (local or cloud) don't serve OpenAI's mini model, so the router and other
        // FAST-tier nodes must run on the same user-selected model there.
        scope.launch(Dispatchers.Main.immediate) {
            combine(settingsStore.activeProvider, settingsStore.activeModel) { provider, model -> provider to model }
                .collect { (provider, model) ->
                    Models.REASONING = model
                    Models.FAST = if (provider == "ollama" || provider == "ollama-cloud") model else "gpt-4o-mini"
                }
        }
    }

    /**
     * Connects the hardcoded default MCP servers plus any user-added custom servers. Runs once;
     * servers added mid-session are connected by SettingsViewModel directly.
     */
    private suspend fun connectMcpServers() {
        val customServers = settingsStore.customServers.first().map {
            McpServer(
                name = it.name,
                url = it.url,
                bearerToken = it.bearerToken,
                tokenProvider = mcpOAuthManager.tokenProviderFor(it.url),
            )
        }
        val connector = McpToolConnector(tools, AndroidLogger)
        for (server in mcpServers + customServers) {
            try {
                connector.connect(server)
            } catch (e: McpUnauthorizedException) {
                // Known server, needs sign-in (no crash; card shows state next time settings opens).
                mcpAuthFailures.update { it + server.name }
            } catch (e: Exception) {
                AndroidLogger.w(TAG, "Failed to connect to MCP server: ${server.name}", e)
            }
        }
    }

    private companion object {
        const val TAG = "AgentBootstrap"
    }
}
