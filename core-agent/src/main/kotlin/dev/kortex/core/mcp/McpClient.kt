package dev.kortex.core.mcp

import dev.kortex.core.log.Logger
import dev.kortex.core.log.d
import dev.kortex.core.log.w
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

class McpException(message: String) : RuntimeException(message)

/** One tool advertised by an MCP server: name, description, and its raw JSON-Schema input. */
data class McpToolDescriptor(
    val name: String,
    val description: String,
    val inputSchema: JsonObject,
)

/**
 * Minimal Model Context Protocol client over Streamable HTTP (JSON-RPC 2.0 POSTs to a
 * single endpoint). Enough of the protocol for tool use: initialize -> tools/list ->
 * tools/call. Servers may answer a POST with plain JSON or with an SSE stream; both are
 * handled. Session id and negotiated protocol version are carried on subsequent requests.
 *
 * Deliberately not a full client: no server-initiated requests, resources, prompts, or
 * long-lived GET streams — those can come later without changing the [dev.kortex.core.tool.Tool]
 * bridge in McpToolSource.kt.
 */
class McpClient(
    private val serverUrl: String,
    private val bearerToken: String? = null,
    private val extraHeaders: Map<String, String> = emptyMap(),
    private val client: HttpClient = defaultMcpHttpClient(),
    private val logger: Logger = Logger.CONSOLE,
) {
    private var nextId = 1
    private var sessionId: String? = null
    private var protocolVersion: String = "2025-03-26"
    private var initialized = false

    suspend fun listTools(): List<McpToolDescriptor> {
        ensureInitialized()
        val result = request("tools/list", buildJsonObject { })
        return (result["tools"] as? JsonArray ?: JsonArray(emptyList())).mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            McpToolDescriptor(
                name = o["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                description = o["description"]?.jsonPrimitive?.contentOrNull ?: "",
                inputSchema = o["inputSchema"] as? JsonObject ?: JsonObject(emptyMap()),
            )
        }
    }

    /** Returns the raw MCP call result; McpToolSource converts it to a ToolResult. */
    suspend fun callTool(name: String, arguments: JsonObject): JsonObject {
        ensureInitialized()
        return request(
            "tools/call",
            buildJsonObject {
                put("name", name)
                put("arguments", arguments)
            },
        )
    }

    private suspend fun ensureInitialized() {
        if (initialized) return
        val result = request(
            "initialize",
            buildJsonObject {
                put("protocolVersion", protocolVersion)
                putJsonObject("capabilities") { }
                putJsonObject("clientInfo") {
                    put("name", "kortex")
                    put("version", "0.1.0")
                }
            },
        )
        result["protocolVersion"]?.jsonPrimitive?.contentOrNull?.let { protocolVersion = it }
        initialized = true
        notify("notifications/initialized")
        logger.d(TAG, "initialized $serverUrl (protocol $protocolVersion)")
    }

    private suspend fun request(method: String, params: JsonObject): JsonObject {
        val id = nextId++
        val body = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", method)
            put("params", params)
        }.toString()
        logger.d(TAG, "-> $method (id=$id) $serverUrl")

        val response = client.post(serverUrl) {
            mcpHeaders()
            setBody(body)
        }
        response.headers["Mcp-Session-Id"]?.let { sessionId = it }

        val contentType = response.contentType()?.let { "${it.contentType}/${it.contentSubtype}" }
        val message = extractJsonRpcMessage(response.body<String>(), contentType, id)

        (message["error"] as? JsonObject)?.let { err ->
            val text = err["message"]?.jsonPrimitive?.contentOrNull ?: err.toString()
            logger.w(TAG, "$method failed: $text")
            throw McpException("MCP $method failed: $text")
        }
        return message["result"] as? JsonObject
            ?: throw McpException("MCP $method returned no result object")
    }

    /** Fire-and-forget JSON-RPC notification (no id, no response expected). */
    private suspend fun notify(method: String) {
        runCatching {
            client.post(serverUrl) {
                mcpHeaders()
                setBody(buildJsonObject { put("jsonrpc", "2.0"); put("method", method) }.toString())
            }
        }
    }

    private fun HttpRequestBuilder.mcpHeaders() {
        contentType(ContentType.Application.Json)
        header(HttpHeaders.Accept, "application/json, text/event-stream")
        header("MCP-Protocol-Version", protocolVersion)
        sessionId?.let { header("Mcp-Session-Id", it) }
        bearerToken?.let { header(HttpHeaders.Authorization, "Bearer $it") }
        extraHeaders.forEach { (k, v) -> header(k, v) }
    }

    companion object {
        private const val TAG = "McpClient"
    }
}

private val json = Json { ignoreUnknownKeys = true }

/**
 * SSE bodies carry one JSON payload per event: `data:` lines concatenated, events
 * separated by blank lines. Non-data fields (`event:`, `id:`, comments) are ignored.
 */
internal fun sseDataPayloads(body: String): List<String> =
    body.replace("\r\n", "\n").split("\n\n").mapNotNull { event ->
        event.lines()
            .filter { it.startsWith("data:") }
            .joinToString("\n") { it.removePrefix("data:").trim() }
            .ifBlank { null }
    }

/**
 * Finds the JSON-RPC message answering request [id] in an HTTP response body that is
 * either a plain JSON document or an SSE stream (which may interleave unrelated
 * notifications before the actual response).
 */
internal fun extractJsonRpcMessage(body: String, contentType: String?, id: Int): JsonObject {
    val payloads = if (contentType?.startsWith("text/event-stream") == true) {
        sseDataPayloads(body)
    } else {
        listOf(body)
    }
    for (payload in payloads) {
        val element = runCatching { json.parseToJsonElement(payload) }.getOrNull() ?: continue
        val messages = when (element) {
            is JsonArray -> element.mapNotNull { it as? JsonObject }
            is JsonObject -> listOf(element)
            else -> emptyList()
        }
        messages.firstOrNull { it["id"]?.jsonPrimitive?.contentOrNull == id.toString() }
            ?.let { return it }
    }
    throw McpException("No JSON-RPC response with id=$id in server reply (content-type=$contentType)")
}

internal fun defaultMcpHttpClient(): HttpClient = HttpClient(OkHttp) {
    install(HttpTimeout) {
        requestTimeoutMillis = 60_000
        connectTimeoutMillis = 15_000
        socketTimeoutMillis = 60_000
    }
}
