package dev.kortex.core

import dev.kortex.core.mcp.McpException
import dev.kortex.core.mcp.extractJsonRpcMessage
import dev.kortex.core.mcp.mcpResultToToolResult
import dev.kortex.core.mcp.schemaFromMcp
import dev.kortex.core.mcp.sseDataPayloads
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.jupiter.api.Test

class McpBridgeTest {

    // --- SSE / JSON-RPC wire parsing ---

    @Test
    fun `splits SSE stream into per-event data payloads`() {
        val body = "event: message\ndata: {\"a\":1}\n\n" +
            "event: message\ndata: {\"b\":2}\ndata: {\"c\":3}\n\n"

        sseDataPayloads(body) shouldBe listOf("{\"a\":1}", "{\"b\":2}\n{\"c\":3}")
    }

    @Test
    fun `extracts the response matching the request id from an SSE stream`() {
        // Servers may interleave notifications before the actual response.
        val body = "event: message\n" +
            "data: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/progress\",\"params\":{}}\n\n" +
            "event: message\n" +
            "data: {\"jsonrpc\":\"2.0\",\"id\":7,\"result\":{\"ok\":true}}\n\n"

        val msg = extractJsonRpcMessage(body, "text/event-stream", 7)

        msg["id"]?.jsonPrimitive?.content shouldBe "7"
    }

    @Test
    fun `extracts a plain JSON response body`() {
        val body = """{"jsonrpc":"2.0","id":1,"result":{"tools":[]}}"""

        val msg = extractJsonRpcMessage(body, "application/json", 1)

        msg["id"]?.jsonPrimitive?.content shouldBe "1"
    }

    @Test
    fun `throws when no message answers the request id`() {
        shouldThrow<McpException> {
            extractJsonRpcMessage("""{"jsonrpc":"2.0","id":2,"result":{}}""", "application/json", 1)
        }
    }

    // --- schema bridging ---

    @Test
    fun `derives top-level params and keeps the raw schema verbatim`() {
        val inputSchema = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("city") {
                    put("type", "string")
                    put("description", "City name")
                }
                putJsonObject("days") { put("type", "integer") }
            }
            putJsonArray("required") { add("city") }
        }

        val schema = schemaFromMcp(inputSchema)

        schema.params shouldHaveSize 2
        val city = schema.params.first { it.name == "city" }
        city.type shouldBe "string"
        city.description shouldBe "City name"
        city.required shouldBe true
        schema.params.first { it.name == "days" }.required shouldBe false
        // The LLM must see the server's schema untouched, not our flattened reconstruction.
        schema.toJsonSchema() shouldBe inputSchema
    }

    // --- result bridging ---

    @Test
    fun `joins text content items into one ToolResult`() {
        val result = buildJsonObject {
            putJsonArray("content") {
                addJsonObject { put("type", "text"); put("text", "line one") }
                addJsonObject { put("type", "text"); put("text", "line two") }
            }
        }

        val toolResult = mcpResultToToolResult(result)

        toolResult.ok shouldBe true
        toolResult.content shouldBe "line one\nline two"
    }

    @Test
    fun `maps isError to a failed ToolResult`() {
        val result = buildJsonObject {
            put("isError", true)
            putJsonArray("content") {
                addJsonObject { put("type", "text"); put("text", "quota exceeded") }
            }
        }

        val toolResult = mcpResultToToolResult(result)

        toolResult.ok shouldBe false
        toolResult.content shouldBe "quota exceeded"
    }
}
