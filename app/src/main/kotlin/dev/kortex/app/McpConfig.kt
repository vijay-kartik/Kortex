package dev.kortex.app

import dev.kortex.core.mcp.McpServer

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
val mcpServers: List<McpServer> = listOf(McpServer(url = "https://youtube-transcript-mcp.ergut.workers.dev/sse", name = "youtube-transcript"))
