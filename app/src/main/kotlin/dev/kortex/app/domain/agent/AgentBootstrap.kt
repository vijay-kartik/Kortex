package dev.kortex.app.domain.agent

import dev.kortex.core.tool.ToolRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * App-wide agent setup that every agent run depends on, whichever screen (or none) is open:
 * applies the user's [disabledTools] and connects MCP servers into the shared [ToolRegistry]
 * through [McpConnections]. Started once from KortexApp. Models aren't set here: each run
 * reads them from [ModelSelection] and passes them in its AgentContext.
 *
 * Runs on the main thread: [ToolRegistry] isn't thread-safe, and [McpConnections] mutates it
 * from the main thread too.
 */
class AgentBootstrap(
    private val scope: CoroutineScope,
    private val tools: ToolRegistry,
    private val disabledTools: Flow<Set<String>>,
    private val mcpConnections: McpConnections,
) {
    private var started = false

    fun start() {
        if (started) return
        started = true

        // Keep the registry in sync with the disabled-tool set, including toggles from settings.
        scope.launch(Dispatchers.Main.immediate) {
            disabledTools.collect { disabled -> tools.setDisabled(disabled) }
        }
        scope.launch(Dispatchers.Main.immediate) {
            mcpConnections.connectAll()
        }
        scope.launch(Dispatchers.Main.immediate) {
            mcpConnections.reconnectOnSignIn()
        }
    }
}
