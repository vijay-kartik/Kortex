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
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * LLM provider for Deepseek Chat/Reasoning models.
 * Compatible with OpenAI API format, targeting https://api.deepseek.com.
 */
class DeepseekProvider(
    private val apiKey: String,
    private val baseUrl: String = "https://api.deepseek.com",
    private val logger: Logger = Logger.CONSOLE,
) : LlmProvider {

    private var client: HttpClient = defaultClient()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    internal constructor(
        apiKey: String,
        baseUrl: String = "https://api.deepseek.com",
        logger: Logger = Logger.CONSOLE,
        client: HttpClient,
    ) : this(apiKey, baseUrl, logger) {
        this.client = client
    }

    override suspend fun complete(req: LlmRequest, logger: Logger?): LlmResponse {
        val log = logger ?: this.logger
        val mappedModel = mapModel(req.model)
        log.d(TAG, "POST /chat/completions model=$mappedModel messages=${req.messages.size} tools=${req.tools.size}")
        return runCatching {
            val response: JsonObject = client.post("$baseUrl/chat/completions") {
                header("Authorization", "Bearer $apiKey")
                contentType(ContentType.Application.Json)
                setBody(buildRequestBody(req.copy(model = mappedModel), stream = false).toString())
            }.body<String>().let { json.parseToJsonElement(it).jsonObject }

            // Surface API-level errors (auth failures, rate limits, bad model, etc.)
            val error = response["error"]?.jsonObject
            if (error != null) {
                val errMsg = error["message"]?.jsonPrimitive?.content ?: "unknown error"
                val errType = error["type"]?.jsonPrimitive?.content ?: "api_error"
                throw IllegalStateException("Deepseek API error ($errType): $errMsg")
            }

            val choices = response["choices"]?.jsonArray
                ?: throw IllegalStateException(
                    "Deepseek API returned no 'choices'. Raw response: ${response.toString().take(500)}"
                )
            val choice = choices.first().jsonObject
            val msg = choice["message"]!!.jsonObject
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

    override fun stream(req: LlmRequest): Flow<LlmChunk> = flow {
        val resp = complete(req)
        if (resp.message.content.isNotEmpty()) emit(LlmChunk.Text(resp.message.content))
        emit(LlmChunk.Done)
    }

    private fun mapModel(model: String): String = when (model) {
        Models.REASONING -> "deepseek-v4-pro"
        Models.FAST -> "deepseek-v4-flash"
        else -> model
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
        private const val TAG = "DeepseekProvider"

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
