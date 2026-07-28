package dev.kortex.core.mcp

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Test

class McpClientTest {

    @Test
    fun `throws McpUnauthorizedException on 401 response`() = runTest {
        val mockEngine = MockEngine {
            respond(
                content = "",
                status = HttpStatusCode.Unauthorized,
                headers = headersOf(HttpHeaders.WWWAuthenticate, "Bearer error=\"invalid_token\"")
            )
        }
        val client = McpClient(
            serverUrl = "http://localhost",
            client = HttpClient(mockEngine)
        )

        val exception = shouldThrow<McpUnauthorizedException> {
            client.listTools()
        }
        exception.wwwAuthenticate shouldBe "Bearer error=\"invalid_token\""
    }

    @Test
    fun `throws normal McpException on non-401 errors`() = runTest {
        val mockEngine = MockEngine {
            respond(
                content = "Internal Server Error",
                status = HttpStatusCode.InternalServerError
            )
        }
        val client = McpClient(
            serverUrl = "http://localhost",
            client = HttpClient(mockEngine)
        )

        val exception = shouldThrow<McpException> {
            client.listTools()
        }
        (exception is McpUnauthorizedException) shouldBe false
    }

    @Test
    fun `tool-exec 401 triggers forceRefresh retry once`() = runTest {
        var attempts = 0
        var forceRefreshCalled = false
        val mockEngine = MockEngine { request ->
            val bodyText = (request.body as io.ktor.http.content.TextContent).text
            val idMatch = Regex("\"id\":(\\d+)").find(bodyText)
            val id = idMatch?.groupValues?.get(1) ?: "null"
            
            if (bodyText.contains("\"method\":\"initialize\"")) {
                respond(
                    content = """
                        {
                            "jsonrpc": "2.0",
                            "id": $id,
                            "result": {
                                "protocolVersion": "2024-11-05"
                            }
                        }
                    """.trimIndent(),
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
            } else if (bodyText.contains("\"method\":\"notifications/initialized\"")) {
                respond(content = "", status = HttpStatusCode.OK)
            } else {
                if (attempts == 0) {
                    attempts++
                    respond(
                        content = "",
                        status = HttpStatusCode.Unauthorized,
                        headers = headersOf(HttpHeaders.WWWAuthenticate, "Bearer")
                    )
                } else {
                    attempts++
                    respond(
                        content = """
                            {
                                "jsonrpc": "2.0",
                                "id": $id,
                                "result": {
                                    "content": [{"type": "text", "text": "success"}]
                                }
                            }
                        """.trimIndent(),
                        headers = headersOf(HttpHeaders.ContentType, "application/json")
                    )
                }
            }
        }

        val client = McpClient(
            serverUrl = "http://localhost",
            client = HttpClient(mockEngine)
        )

        val tokenProvider: suspend (Boolean) -> String? = { forceRefresh ->
            forceRefreshCalled = forceRefresh
            "new_token"
        }

        val server = McpServer(
            name = "test_server",
            url = "http://localhost",
            tokenProvider = tokenProvider
        )
        val desc = McpToolDescriptor("test_tool", "desc", JsonObject(emptyMap()))
        val tool = mcpTool(client, desc, server)

        val result = tool.execute(JsonObject(emptyMap()))

        result.ok shouldBe true
        result.content shouldBe "success"
        forceRefreshCalled shouldBe true
        attempts shouldBe 2
    }
}
