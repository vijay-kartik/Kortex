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

/**
 * OpenAI-compatible embedding provider. Can point to OpenAI, Ollama, or Ollama Cloud.
 */
class OpenAiEmbeddingProvider(
    private val apiKey: String,
    private val model: String = "nomic-embed-text",
    private val baseUrl: String = "https://api.openai.com/v1",
    private val logger: Logger = Logger.CONSOLE
) : EmbeddingProvider {

    private val client: HttpClient = OpenAiProvider.defaultClient()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    override suspend fun embed(text: String): FloatArray {
        logger.d(TAG, "POST /embeddings model=$model textLength=${text.length}")
        return runCatching {
            val requestBody = buildJsonObject {
                put("model", model)
                put("input", text)
                put("dimensions", 384)
            }.toString()

            val response: JsonObject = client.post("$baseUrl/embeddings") {
                header("Authorization", "Bearer $apiKey")
                contentType(ContentType.Application.Json)
                setBody(requestBody)
            }.body<String>().let { json.parseToJsonElement(it).jsonObject }

            (response["error"] as? JsonObject)?.let { err ->
                val errText = err["message"]?.jsonPrimitive?.contentOrNull ?: err.toString()
                throw LlmException("OpenAI embeddings failed: $errText")
            }

            val dataArray = response["data"]?.jsonArray
                ?: throw LlmException("OpenAI response had no data: $response")
            
            val firstData = dataArray.firstOrNull()?.jsonObject
                ?: throw LlmException("OpenAI data was empty: $response")

            val embeddingArray = firstData["embedding"]?.jsonArray
                ?: throw LlmException("OpenAI data had no embedding: $firstData")

            val expectedSize = 384
            val floats = FloatArray(expectedSize) { i ->
                if (i < embeddingArray.size) embeddingArray[i].jsonPrimitive.double.toFloat() else 0f
            }
            floats
        }.onSuccess {
            logger.d(TAG, "Successfully embedded vector of size ${it.size}")
        }.onFailure { err ->
            logger.e(TAG, "request to $baseUrl/embeddings failed: ${err.message}", err)
        }.getOrThrow()
    }

    companion object {
        private const val TAG = "OpenAiEmbeddingProvider"
    }
}
