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
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.utils.io.readUTF8Line
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
    /** The `type: "file"` content part (base64 PDFs) is an OpenAI-only Chat Completions
     *  extension — Ollama's OpenAI-compat endpoint rejects it as an invalid message. Set
     *  false for non-OpenAI-compatible hosts so PDFs fall back to a text placeholder. */
    private val supportsPdfAttachments: Boolean = true,
) : LlmProvider {

    override val supportsStreaming: Boolean = true

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

    override fun stream(req: LlmRequest): Flow<LlmChunk> = stream(req, null)

    /** Reads OpenAI-compatible server-sent events as they arrive, so cancellation closes
     *  the underlying HTTP call instead of merely hiding an already-completed answer. */
    override fun stream(req: LlmRequest, logger: Logger?): Flow<LlmChunk> = flow {
        val log = logger ?: this@OpenAiProvider.logger
        log.d(TAG, "POST /chat/completions (stream) model=${req.model} messages=${req.messages.size} tools=${req.tools.size}")
        val channel = client.post("$baseUrl/chat/completions") {
            header("Authorization", "Bearer $apiKey")
            contentType(ContentType.Application.Json)
            setBody(buildRequestBody(req, stream = true).toString())
        }.bodyAsChannel()

        while (true) {
            val line = channel.readUTF8Line() ?: break
            // OpenAI/Deepseek use SSE (`data: {...}`); Ollama commonly returns the same
            // OpenAI-shaped chunks as newline-delimited JSON. Accept both wire formats.
            val raw = line.trim()
            val data = when {
                raw.startsWith("data:") -> raw.removePrefix("data:").trimStart()
                raw.startsWith("{") -> raw
                else -> continue
            }
            if (data == "[DONE]") break
            val event = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull() ?: continue
            event["error"]?.jsonObject?.let { error ->
                throw LlmException("OpenAI request failed: ${error["message"]?.jsonPrimitive?.contentOrNull ?: error}")
            }
            val choice = event["choices"]?.jsonArray?.firstOrNull()?.jsonObject
            val delta = choice?.get("delta")?.jsonObject
                ?: choice?.get("message")?.jsonObject
                ?: event["message"]?.jsonObject
            if (delta != null) {
                delta["content"]?.jsonPrimitive?.contentOrNull
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { emit(LlmChunk.Text(it)) }
                delta["tool_calls"]?.jsonArray?.forEach { raw ->
                    val call = raw.jsonObject
                    val function = call["function"]?.jsonObject
                    emit(
                        LlmChunk.ToolCallDelta(
                            index = call["index"]?.jsonPrimitive?.int ?: 0,
                            id = call["id"]?.jsonPrimitive?.contentOrNull,
                            name = function?.get("name")?.jsonPrimitive?.contentOrNull,
                            argsDelta = function?.get("arguments")?.jsonPrimitive?.contentOrNull.orEmpty(),
                        )
                    )
                }
            }
            if (event["done"]?.jsonPrimitive?.contentOrNull == "true") break
        }
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
                        attachment.mimeType == "application/pdf" && supportsPdfAttachments -> {
                            add(buildJsonObject {
                                put("type", "file")
                                putJsonObject("file") {
                                    put("filename", attachment.filename ?: "document.pdf")
                                    put("file_data", "data:application/pdf;base64,${attachment.dataBase64}")
                                }
                            })
                        }
                        attachment.mimeType == "application/pdf" -> {
                            // Host has no PDF content type (e.g. Ollama) — render pages to
                            // images instead of a text placeholder, so the model actually
                            // sees the document rather than guessing at a local file path.
                            val tmp = java.io.File.createTempFile("attach_pdf", ".pdf").apply {
                                writeBytes(java.util.Base64.getDecoder().decode(attachment.dataBase64))
                            }
                            try {
                                renderPdfPagesAsImages(tmp, attachment.filename ?: "document").forEach { page ->
                                    add(buildJsonObject {
                                        put("type", "image_url")
                                        putJsonObject("image_url") {
                                            put("url", "data:${page.mimeType};base64,${page.dataBase64}")
                                        }
                                    })
                                }
                            } finally {
                                tmp.delete()
                            }
                        }
                        // Dictated voice notes carry a transcript and no audio bytes; send the
                        // text so any chat model can read it (input_audio needs audio-capable
                        // models and real bytes).
                        attachment.mimeType.startsWith("audio/") && attachment.transcript != null -> {
                            add(buildJsonObject {
                                put("type", "text")
                                put("text", "[Voice message transcript]: ${attachment.transcript}")
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
