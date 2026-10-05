package dev.kortex.app.data.ai

import dev.kortex.app.BuildConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Vercel AI Gateway, for TypeSafe AI's Jev. Jev is an *evaluation* model, not a chat model: it
 * takes some state plus typed questions (boolean / choice / score) and returns probabilities, so it
 * goes through `POST /v1/evaluate` rather than chat completions.
 * Docs: https://vercel.com/docs/ai-gateway/modalities/evaluation
 *
 * The key comes from `AI_GATEWAY_API_KEY` in local.properties. Nothing calls this yet besides the
 * Settings test button.
 */
@Singleton
class AiGatewayClient @Inject constructor() {

    val isConfigured: Boolean get() = API_KEY.isNotBlank()

    private val json = Json { ignoreUnknownKeys = true }

    private val client by lazy {
        HttpClient(OkHttp) {
            install(HttpTimeout) {
                requestTimeoutMillis = 30_000
                connectTimeoutMillis = 15_000
            }
        }
    }

    /**
     * Evaluates [state] against [questions] (keyed by question id, each `{type, instructions,
     * criteria?}`) and returns the `answers` object, keyed the same way. Throws
     * [AiGatewayException] on a missing key or an error from the gateway.
     */
    suspend fun evaluate(
        state: JsonElement,
        questions: JsonObject,
        model: String = JEV_MODEL,
    ): JsonObject {
        if (!isConfigured) throw AiGatewayException("AI_GATEWAY_API_KEY is not set in local.properties")
        val body = buildJsonObject {
            put("model", model)
            put("state", state)
            put("questions", questions)
        }
        val response = client.post("$BASE_URL/v1/evaluate") {
            header("Authorization", "Bearer $API_KEY")
            contentType(ContentType.Application.Json)
            setBody(body.toString())
        }
        val text = response.bodyAsText()
        if (!response.status.isSuccess()) {
            throw AiGatewayException("HTTP ${response.status.value}: ${errorMessage(text)}")
        }
        val parsed = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
            ?: throw AiGatewayException("Unreadable response: ${text.take(300)}")
        return parsed["answers"] as? JsonObject
            ?: throw AiGatewayException("No 'answers' in response: ${text.take(300)}")
    }

    /** Pulls a readable message out of the gateway's error bodies, which come in a few shapes. */
    private fun errorMessage(body: String): String {
        val obj = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return body.take(300).ifBlank { "empty response" }
        val error = obj["error"]
        return when {
            error is JsonPrimitive -> error.contentOrNull
            error is JsonObject -> error["message"]?.jsonPrimitive?.contentOrNull
            else -> obj["message"]?.jsonPrimitive?.contentOrNull
        } ?: body.take(300)
    }

    companion object {
        const val BASE_URL = "https://ai-gateway.vercel.sh"
        const val JEV_MODEL = "typesafe-ai/jev"
        private val API_KEY = BuildConfig.AI_GATEWAY_API_KEY
    }
}

class AiGatewayException(message: String) : Exception(message)
