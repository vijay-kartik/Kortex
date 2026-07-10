package dev.kortex.app

import dev.kortex.core.log.Logger
import dev.kortex.core.log.w
import dev.kortex.core.mcp.McpServer
import dev.kortex.core.mcp.resolveComposioServer
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

const val COMPOSIO_GMAIL_SERVER_NAME = "composio-gmail"

/**
 * Resolves a Composio MCP server for Gmail using the API key configured in the MCP
 * settings sheet. Returns null (and logs) if the key isn't set yet — same "best effort"
 * contract as the other MCP servers in [mcpServers].
 *
 * Composio now exposes a direct MCP endpoint at `connect.composio.dev/mcp` — no
 * session-minting step is needed; the `x-consumer-api-key` header handles auth.
 */
suspend fun resolveComposioGmailServer(store: McpStore, logger: Logger): McpServer? {
    // Trimmed defensively: header values can't contain whitespace/newlines, and copy-pasted
    // keys/ids routinely carry a trailing one from the source they were copied out of.
    val apiKey = store.composioApiKey.first()?.trim()?.takeIf { it.isNotBlank() } ?: return null
    return runCatching {
        resolveComposioServer(
            apiKey = apiKey,
            serverName = COMPOSIO_GMAIL_SERVER_NAME,
        )
    }.onFailure { logger.w("Composio", "session create failed: ${it.message}", it) }
        .getOrNull()
}
