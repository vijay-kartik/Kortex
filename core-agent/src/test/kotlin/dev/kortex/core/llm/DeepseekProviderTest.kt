package dev.kortex.core.llm

import dev.kortex.core.state.Message
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import io.ktor.http.content.OutgoingContent

class DeepseekProviderTest {

    @Test
    fun `completes with correct model mapping and response structure`() = runTest {
        val mockEngine = MockEngine { request ->
            val auth = request.headers["Authorization"]
            auth shouldBe "Bearer mock-api-key"
            
            val body = request.body
            val bodyString = when (body) {
                is OutgoingContent.ByteArrayContent -> body.bytes().decodeToString()
                is OutgoingContent.NoContent -> ""
                else -> body.toString()
            }
            bodyString shouldContain "\"model\":\"deepseek-v4-pro\""

            respond(
                content = """
                    {
                      "choices": [
                        {
                          "message": {
                            "role": "assistant",
                            "content": "Deepseek response content"
                          }
                        }
                      ],
                      "usage": {
                        "prompt_tokens": 10,
                        "completion_tokens": 15
                      }
                    }
                """.trimIndent(),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            )
        }

        val client = HttpClient(mockEngine) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
        }

        val provider = DeepseekProvider(
            apiKey = "mock-api-key",
            baseUrl = "https://api.deepseek.com",
            client = client
        )

        val req = LlmRequest(
            model = Models.REASONING, // Maps to deepseek-v4-pro
            messages = listOf(Message(Message.Role.USER, "Hello"))
        )

        val response = provider.complete(req)
        response.message.content shouldBe "Deepseek response content"
        response.inputTokens shouldBe 10
        response.outputTokens shouldBe 15
    }
}
