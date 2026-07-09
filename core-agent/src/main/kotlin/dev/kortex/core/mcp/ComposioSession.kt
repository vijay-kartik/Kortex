package dev.kortex.core.mcp

import io.ktor.client.call.body
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

private const val COMPOSIO_TOOL_ROUTER_URL = "https://backend.composio.dev/api/v3.1/tool_router/session"
private val composioJson = Json { ignoreUnknownKeys = true }

class ComposioException(message: String) : RuntimeException(message)

/**
 * Composio dropped its static MCP server: a Tool Router session must be minted per
 * connection via an authenticated REST call, and its `mcp.url` used as the MCP endpoint
 * with an `x-api-key` header on every request. [userId] must match whatever user_id the
 * toolkit connections (e.g. Gmail) were authorized under in Composio — the session only
 * sees tools/accounts scoped to that id.
 * https://docs.composio.dev/reference/api-reference/tool-router/postToolRouterSession
 */
suspend fun resolveComposioToolRouterServer(
    apiKey: String,
    userId: String,
    toolkits: List<String>,
    serverName: String,
): McpServer {
    val raw = defaultMcpHttpClient().use { client ->
        val response = client.post(COMPOSIO_TOOL_ROUTER_URL) {
            header("x-api-key", apiKey)
            contentType(ContentType.Application.Json)
            setBody(
                buildJsonObject {
                    put("user_id", userId)
                    putJsonObject("toolkits") {
                        putJsonArray("enable") { toolkits.forEach { add(it) } }
                    }
                }.toString()
            )
        }
        val body = response.body<String>()
        if (!response.status.isSuccess()) {
            throw ComposioException("Composio session create failed (${response.status}): $body")
        }
        body
    }
    val mcpUrl = runCatching { composioJson.parseToJsonElement(raw).jsonObject }
        .getOrNull()
        ?.get("mcp")?.jsonObject?.get("url")?.jsonPrimitive?.contentOrNull
        ?: throw ComposioException("Composio session response had no mcp.url: $raw")

    return McpServer(
        name = serverName,
        url = mcpUrl,
        extraHeaders = mapOf("x-api-key" to apiKey),
    )
}
