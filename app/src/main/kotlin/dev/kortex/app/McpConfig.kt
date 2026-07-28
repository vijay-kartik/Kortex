package dev.kortex.app

import dev.kortex.core.log.Logger
import dev.kortex.core.log.w
import dev.kortex.core.mcp.McpServer
import dev.kortex.core.tool.ToolRegistry
import kotlinx.coroutines.flow.first

/**
 * Third-party MCP servers whose tools the chat agent can call. Add an entry and its tools
 * appear to the LLM automatically at startup as `<name>_<tool>` (e.g. `linear_create_issue`),
 * gated by the ToolGovernor at the server's risk level — the default MEDIUM means every
 * call shows the user an approval dialog before it runs.
 *
 * Example:
 *   McpServer(
 *       name = "deepwiki",
 *       url = "https://mcp.deepwiki.com/mcp",
 *   ),
 *   McpServer(
 *       name = "linear",
 *       url = "https://mcp.linear.app/mcp",
 *       bearerToken = BuildConfig.LINEAR_TOKEN,   // wire secrets via local.properties, never hard-code
 *   ),
 */
val mcpServers: List<McpServer> = listOf(
    McpServer(url = "https://mcp.porteden.com/mcp", name = "porteden", bearerToken = "pe_J84IVYFzrU7FjOMBm6Tc29I9tK2hX0DwpBhABx40djk")
)

