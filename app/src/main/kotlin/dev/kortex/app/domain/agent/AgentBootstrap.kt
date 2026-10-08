package dev.kortex.app.domain.agent

import dev.kortex.app.data.settings.SettingsStore
import dev.kortex.core.llm.Models
import dev.kortex.core.tool.ToolRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * App-wide agent setup that every agent run depends on, whichever screen (or none) is open:
 * applies the user's disabled tools, connects MCP servers into the shared [ToolRegistry] through
 * [McpConnections], and keeps the global [Models] in line with the selected provider. Started
 * once from KortexApp.
 *
 * Runs on the main thread: [ToolRegistry] isn't thread-safe, and [McpConnections] mutates it
 * from the main thread too.
 */
class AgentBootstrap(
    private val scope: CoroutineScope,
    private val tools: ToolRegistry,
    private val settingsStore: SettingsStore,
    private val mcpConnections: McpConnections,
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
            mcpConnections.connectAll()
        }
        scope.launch(Dispatchers.Main.immediate) {
            mcpConnections.reconnectOnSignIn()
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
}
