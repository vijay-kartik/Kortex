package dev.kortex.core.llm

import dev.kortex.core.log.Logger
import dev.kortex.core.log.d
import dev.kortex.core.log.e
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class OllamaEmbeddingProvider(
    private val model: String,
    private val baseUrl: String,
    private val logger: Logger = Logger.CONSOLE
) : EmbeddingProvider {

    private val client: HttpClient = OpenAiProvider.defaultClient()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    override suspend fun embed(text: String): FloatArray {
        // Strip /v1 if present in baseUrl, because native Ollama uses /api
        val hostUrl = baseUrl.removeSuffix("/v1").removeSuffix("/")
        logger.d(TAG, "POST $hostUrl/api/embed model=$model textLength=${text.length}")
        
        return runCatching {
            val requestBody = buildJsonObject {
                put("model", model)
                put("input", text)
            }.toString()

            val response: JsonObject = client.post("$hostUrl/api/embed") {
                contentType(ContentType.Application.Json)
                setBody(requestBody)
            }.body<String>().let { json.parseToJsonElement(it).jsonObject }

            (response["error"] as? JsonObject)?.let { err ->
                val errText = err["message"]?.jsonPrimitive?.contentOrNull ?: err.toString()
                throw LlmException("Ollama embeddings failed: $errText")
            }
            if (response.containsKey("error")) {
                throw LlmException("Ollama embeddings failed: ${response["error"]?.jsonPrimitive?.contentOrNull}")
            }

            val embeddingsArray = response["embeddings"]?.jsonArray
                ?: throw LlmException("Ollama response had no embeddings: $response")
            
            val firstEmbedding = embeddingsArray.firstOrNull()?.jsonArray
                ?: throw LlmException("Ollama embeddings array was empty: $response")

            val expectedSize = 384
            val floats = FloatArray(expectedSize) { i ->
                if (i < firstEmbedding.size) firstEmbedding[i].jsonPrimitive.double.toFloat() else 0f
            }
            floats
        }.onSuccess {
            logger.d(TAG, "Successfully embedded vector of size ${it.size}")
        }.onFailure { err ->
            logger.e(TAG, "request to $baseUrl/api/embed failed: ${err.message}", err)
        }.getOrThrow()
    }

    companion object {
        private const val TAG = "OllamaEmbeddingProvider"
    }
}
