package dev.kortex.core.tool

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** How dangerous a tool is — drives the Human-in-the-Loop gate (pattern 13). */
enum class RiskLevel { LOW, MEDIUM, HIGH }

/** Result of running a tool; fed back to the LLM as a TOOL message. */
data class ToolResult(val ok: Boolean, val content: String)

/**
 * Pattern 5: Tool Use. A tool is typed, self-describing (so we can emit a JSON schema
 * for function calling), and tagged with a risk level so the governor can gate it.
 */
interface Tool {
    val name: String
    val description: String
    val parameters: ToolSchema
    val risk: RiskLevel get() = RiskLevel.LOW
    suspend fun execute(args: JsonObject): ToolResult
}

/** Minimal JSON-schema model for function-calling parameters. */
data class ToolParam(val name: String, val type: String, val description: String, val required: Boolean = true)

data class ToolSchema(val params: List<ToolParam>) {
    /** Render as the JSON schema shape providers expect for function calling. */
    fun toJsonSchema(): JsonObject = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            params.forEach { p ->
                put(p.name, buildJsonObject {
                    put("type", p.type)
                    put("description", p.description)
                })
            }
        })
        put("required", kotlinx.serialization.json.JsonArray(
            params.filter { it.required }.map { JsonPrimitive(it.name) }
        ))
    }
}
