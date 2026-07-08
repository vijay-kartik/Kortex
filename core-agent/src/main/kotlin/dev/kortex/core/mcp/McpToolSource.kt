package dev.kortex.core.mcp

import dev.kortex.core.log.Logger
import dev.kortex.core.log.i
import dev.kortex.core.log.w
import dev.kortex.core.tool.RiskLevel
import dev.kortex.core.tool.Tool
import dev.kortex.core.tool.ToolParam
import dev.kortex.core.tool.ToolRegistry
import dev.kortex.core.tool.ToolResult
import dev.kortex.core.tool.ToolSchema
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** A third-party MCP server the agent may pull tools from. */
data class McpServer(
    val name: String,
    val url: String,
    val bearerToken: String? = null,
    /** Third-party code answers these calls, so default to MEDIUM: the governor then routes
     *  every invocation through user approval (pattern 13). Lower to LOW only for servers
     *  you trust to be side-effect-free. */
    val risk: RiskLevel = RiskLevel.MEDIUM,
)

/**
 * Connects MCP servers and registers each of their tools into a [ToolRegistry], named
 * `<server>_<tool>` so servers can't collide with builtins or each other. Registered tools
 * are ordinary [Tool]s: the LLM sees their schema via function calling, and every call
 * still passes through the ToolGovernor (budget, validation, risk approval, audit).
 */
class McpToolConnector(
    private val registry: ToolRegistry,
    private val logger: Logger = Logger.CONSOLE,
) {
    /** Best-effort: a dead server logs a warning and contributes zero tools. */
    suspend fun connectAll(servers: List<McpServer>): Int = servers.sumOf { connect(it) }

    suspend fun connect(server: McpServer): Int = runCatching {
        val client = McpClient(server.url, server.bearerToken, logger = logger)
        val tools = client.listTools()
        tools.forEach { registry.register(mcpTool(client, it, server)) }
        logger.i(TAG, "'${server.name}': ${tools.size} tool(s) registered [${tools.joinToString { it.name }}]")
        tools.size
    }.getOrElse { err ->
        logger.w(TAG, "'${server.name}' unavailable, continuing without it: ${err.message}")
        0
    }

    companion object {
        private const val TAG = "Mcp"
    }
}

/** Adapts one MCP tool descriptor to the agent's [Tool] contract. */
fun mcpTool(client: McpClient, desc: McpToolDescriptor, server: McpServer): Tool = object : Tool {
    // Function-calling names must match [a-zA-Z0-9_-]{1,64}.
    override val name = "${sanitize(server.name)}_${sanitize(desc.name)}".take(64)
    override val description = desc.description
    override val parameters = schemaFromMcp(desc.inputSchema)
    override val risk = server.risk
    override suspend fun execute(args: JsonObject): ToolResult =
        mcpResultToToolResult(client.callTool(desc.name, args))
}

private fun sanitize(s: String) = s.replace(Regex("[^A-Za-z0-9_-]"), "_")

/**
 * Derives the flat top-level param list (for the governor's required-param check) while
 * carrying the server's full JSON Schema verbatim via [ToolSchema.raw], so nested shapes
 * survive the round trip to the LLM.
 */
internal fun schemaFromMcp(inputSchema: JsonObject): ToolSchema {
    val properties = inputSchema["properties"] as? JsonObject ?: JsonObject(emptyMap())
    val required = (inputSchema["required"] as? JsonArray)
        ?.mapNotNull { it.jsonPrimitive.contentOrNull }
        ?.toSet()
        ?: emptySet()
    val params = properties.map { (name, spec) ->
        val o = spec as? JsonObject ?: JsonObject(emptyMap())
        ToolParam(
            name = name,
            type = o["type"]?.jsonPrimitive?.contentOrNull ?: "string",
            description = o["description"]?.jsonPrimitive?.contentOrNull ?: "",
            required = name in required,
        )
    }
    return ToolSchema(params, raw = inputSchema)
}

/**
 * MCP tool results are a content array (text, image, audio, resource items) plus an
 * isError flag. Text and embedded-resource text are joined for the LLM; binary content
 * is out of scope for now.
 */
internal fun mcpResultToToolResult(result: JsonObject): ToolResult {
    val isError = result["isError"]?.jsonPrimitive?.booleanOrNull ?: false
    val text = (result["content"] as? JsonArray)
        ?.mapNotNull { item ->
            val o = item as? JsonObject ?: return@mapNotNull null
            when (o["type"]?.jsonPrimitive?.contentOrNull) {
                "text" -> o["text"]?.jsonPrimitive?.contentOrNull
                "resource" -> (o["resource"] as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull
                else -> null
            }
        }
        ?.joinToString("\n")
        ?.ifBlank { null }
    val fallback = result["structuredContent"]?.toString()
    return ToolResult(ok = !isError, content = text ?: fallback ?: "(no content)")
}
