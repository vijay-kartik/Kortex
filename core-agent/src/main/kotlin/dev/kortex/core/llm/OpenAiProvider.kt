package dev.kortex.core.llm

import dev.kortex.core.log.Logger
import dev.kortex.core.log.d
import dev.kortex.core.log.e
import dev.kortex.core.state.Message
import dev.kortex.core.state.ToolCall
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

class LlmException(message: String) : RuntimeException(message)

/**
 * Default LLM provider: OpenAI Chat Completions API with function calling.
 * Implements the provider-agnostic [LlmProvider] contract, so a Claude/Gemini/on-device
 * provider can be swapped in without touching the agent graph.
 *
 * The API key is injected (never hard-coded); the Android app reads it from a secure
 * store / local.properties and passes it here.
 */
class OpenAiProvider(
    private val apiKey: String,
    private val baseUrl: String = "https://api.openai.com/v1",
    private val logger: Logger = Logger.CONSOLE,
) : LlmProvider {

    private val client: HttpClient = defaultClient()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    override suspend fun complete(req: LlmRequest, logger: Logger?): LlmResponse {
        val log = logger ?: this.logger
        log.d(TAG, "POST /chat/completions model=${req.model} messages=${req.messages.size} tools=${req.tools.size}")
        return runCatching {
            val response: JsonObject = client.post("$baseUrl/chat/completions") {
                header("Authorization", "Bearer $apiKey")
                contentType(ContentType.Application.Json)
                setBody(buildRequestBody(req, stream = false).toString())
            }.body<String>().let { json.parseToJsonElement(it).jsonObject }

            (response["error"] as? JsonObject)?.let { err ->
                val text = err["message"]?.jsonPrimitive?.contentOrNull ?: err.toString()
                throw LlmException("OpenAI request failed: $text")
            }
            val choice = response["choices"]?.jsonArray?.firstOrNull()?.jsonObject
                ?: throw LlmException("OpenAI response had no choices: $response")
            val msg = choice["message"]?.jsonObject
                ?: throw LlmException("OpenAI choice had no message: $choice")
            val usage = response["usage"]?.jsonObject

            LlmResponse(
                message = msg.toDomainMessage(),
                inputTokens = usage?.get("prompt_tokens")?.jsonPrimitive?.int ?: 0,
                outputTokens = usage?.get("completion_tokens")?.jsonPrimitive?.int ?: 0,
            )
        }.onSuccess { resp ->
            val parts = buildList {
                add("${resp.inputTokens}in/${resp.outputTokens}out tokens")
                if (resp.message.content.isNotBlank()) add("content=\"${resp.message.content}\"")
                if (resp.message.toolCalls.isNotEmpty()) {
                    add("tool_calls=[${resp.message.toolCalls.joinToString { "${it.name}(${it.argumentsJson})" }}]")
                }
            }
            log.d(TAG, "response: ${parts.joinToString(", ")}")
        }.onFailure { err ->
            log.e(TAG, "request to $baseUrl failed: ${err.message}", err)
        }.getOrThrow()
    }

    /** Minimal streaming: for now we complete() and emit the text once. Real SSE in a later pass. */
    override fun stream(req: LlmRequest): Flow<LlmChunk> = flow {
        val resp = complete(req)
        if (resp.message.content.isNotEmpty()) emit(LlmChunk.Text(resp.message.content))
        emit(LlmChunk.Done)
    }

    // --- request building ---

    private fun buildRequestBody(req: LlmRequest, stream: Boolean): JsonObject = buildJsonObject {
        put("model", req.model)
        put("temperature", req.temperature)
        put("max_tokens", req.maxTokens)
        if (stream) put("stream", true)
        putJsonArray("messages") { req.messages.forEach { add(it.toApiMessage()) } }
        if (req.tools.isNotEmpty()) {
            putJsonArray("tools") {
                req.tools.forEach { tool ->
                    add(buildJsonObject {
                        put("type", "function")
                        putJsonObject("function") {
                            put("name", tool.name)
                            put("description", tool.description)
                            put("parameters", tool.parameters.toJsonSchema())
                        }
                    })
                }
            }
        }
    }

    private fun Message.toApiMessage(): JsonObject = buildJsonObject {
        put("role", role.name.lowercase())
        
        if (attachments.isEmpty()) {
            put("content", content)
        } else {
            putJsonArray("content") {
                if (content.isNotEmpty()) {
                    add(buildJsonObject {
                        put("type", "text")
                        put("text", content)
                    })
                }
                attachments.forEach { attachment ->
                    when {
                        attachment.mimeType.startsWith("image/") -> {
                            add(buildJsonObject {
                                put("type", "image_url")
                                putJsonObject("image_url") {
                                    put("url", "data:${attachment.mimeType};base64,${attachment.dataBase64}")
                                }
                            })
                        }
                        attachment.mimeType == "application/pdf" -> {
                            add(buildJsonObject {
                                put("type", "file")
                                putJsonObject("file") {
                                    put("filename", attachment.filename ?: "document.pdf")
                                    put("file_data", "data:application/pdf;base64,${attachment.dataBase64}")
                                }
                            })
                        }
                        attachment.mimeType.startsWith("audio/") -> {
                            add(buildJsonObject {
                                put("type", "input_audio")
                                putJsonObject("input_audio") {
                                    put("data", attachment.dataBase64)
                                    put("format", attachment.mimeType.substringAfter("/"))
                                }
                            })
                        }
                        else -> {
                            // Fallback for unsupported types if passed
                            add(buildJsonObject {
                                put("type", "text")
                                put("text", "Attached file: ${attachment.filename ?: "unknown"} (${attachment.mimeType})")
                            })
                        }
                    }
                }
            }
        }

        if (toolCallId != null) put("tool_call_id", toolCallId)
        if (toolCalls.isNotEmpty()) {
            putJsonArray("tool_calls") {
                toolCalls.forEach { tc ->
                    add(buildJsonObject {
                        put("id", tc.id)
                        put("type", "function")
                        putJsonObject("function") {
                            put("name", tc.name)
                            put("arguments", tc.argumentsJson)
                        }
                    })
                }
            }
        }
    }

    // --- response mapping ---

    private fun JsonObject.toDomainMessage(): Message {
        val content = (this["content"] as? JsonPrimitive)
            ?.takeUnless { it is kotlinx.serialization.json.JsonNull }
            ?.content ?: ""
        val toolCalls = (this["tool_calls"] as? JsonArray)?.map { el ->
            val o = el.jsonObject
            val fn = o["function"]!!.jsonObject
            ToolCall(
                id = o["id"]!!.jsonPrimitive.content,
                name = fn["name"]!!.jsonPrimitive.content,
                argumentsJson = fn["arguments"]!!.jsonPrimitive.content,
            )
        } ?: emptyList()
        return Message(Message.Role.ASSISTANT, content, toolCalls = toolCalls)
    }

    companion object {
        private const val TAG = "OpenAiProvider"

        fun defaultClient(): HttpClient = HttpClient(OkHttp) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            install(HttpTimeout) {
                requestTimeoutMillis = 120_000
                connectTimeoutMillis = 30_000
                socketTimeoutMillis = 120_000
            }
        }
    }
}
