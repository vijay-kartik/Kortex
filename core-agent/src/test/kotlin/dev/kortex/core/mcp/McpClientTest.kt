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
}
