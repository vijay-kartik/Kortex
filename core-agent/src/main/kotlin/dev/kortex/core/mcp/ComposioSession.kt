package dev.kortex.core.mcp

private const val COMPOSIO_MCP_URL = "https://connect.composio.dev/mcp"

class ComposioException(message: String) : RuntimeException(message)

/**
 * Returns an [McpServer] pointing at Composio's direct MCP endpoint.
 * Authentication is via the `x-consumer-api-key` header.
 *
 * Composio now exposes a single MCP URL (`connect.composio.dev/mcp`) that
 * handles tool routing internally — no session-minting step is needed.
 */
fun resolveComposioServer(
    apiKey: String,
    serverName: String,
): McpServer = McpServer(
    name = serverName,
    url = COMPOSIO_MCP_URL,
    extraHeaders = mapOf("x-consumer-api-key" to apiKey),
)
