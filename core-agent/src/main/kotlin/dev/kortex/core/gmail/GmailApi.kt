package dev.kortex.core.gmail

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Base64

private const val GMAIL_BASE = "https://gmail.googleapis.com/gmail/v1/users/me"
private val gmailJson = Json { ignoreUnknownKeys = true }

/** Thrown when the Gmail API returns a non-success HTTP status. */
class GmailApiException(val statusCode: Int, message: String) : RuntimeException(message)

data class GmailMessage(
    val id: String,
    val threadId: String,
    val subject: String,
    val from: String,
    val to: String,
    val cc: String,
    val date: String,
    val snippet: String,
    val bodyText: String,
    val bodyHtml: String?,
    val attachments: List<GmailAttachment>,
    val labelIds: List<String>,
)

data class GmailAttachment(
    val attachmentId: String,
    val filename: String,
    val mimeType: String,
    val size: Int,
)

/**
 * Lightweight Gmail REST API client.
 *
 * All methods require a valid OAuth2 access token with at least the
 * `https://www.googleapis.com/auth/gmail.readonly` scope.
 */
class GmailApi(
    private val client: HttpClient = defaultGmailClient(),
) {

    /**
     * Search for messages matching a Gmail search query.
     * Returns a list of message IDs (call [getMessage] for full content).
     *
     * @param query Gmail search syntax, e.g. `"from:user@example.com is:unread"`.
     * @param maxResults cap on the number of message IDs returned (1–500, default 10).
     * @throws GmailApiException on non-2xx responses (e.g. 401 for expired tokens).
     */
    suspend fun listMessages(
        accessToken: String,
        query: String,
        maxResults: Int = 10,
    ): List<String> {
        val response = client.get("$GMAIL_BASE/messages") {
            header("Authorization", "Bearer $accessToken")
            parameter("q", query)
            parameter("maxResults", maxResults.coerceIn(1, 500))
        }
        val body = response.body<String>()
        if (!response.status.isSuccess()) {
            throw GmailApiException(response.status.value, "Gmail list failed (${response.status}): $body")
        }
        val json = gmailJson.parseToJsonElement(body).jsonObject
        return json["messages"]?.jsonArray?.mapNotNull {
            it.jsonObject["id"]?.jsonPrimitive?.content
        } ?: emptyList()
    }

    /**
     * Fetch the full content of a single message.
     *
     * @throws GmailApiException on non-2xx responses.
     */
    suspend fun getMessage(
        accessToken: String,
        messageId: String,
    ): GmailMessage {
        val response = client.get("$GMAIL_BASE/messages/$messageId") {
            header("Authorization", "Bearer $accessToken")
            parameter("format", "full")
        }
        val body = response.body<String>()
        if (!response.status.isSuccess()) {
            throw GmailApiException(response.status.value, "Gmail get failed (${response.status}): $body")
        }
        return parseMessage(gmailJson.parseToJsonElement(body).jsonObject)
    }

    /**
     * Download a large attachment whose data wasn't inlined in the message payload.
     *
     * @return raw decoded bytes of the attachment.
     * @throws GmailApiException on non-2xx responses.
     */
    suspend fun getAttachment(
        accessToken: String,
        messageId: String,
        attachmentId: String,
    ): ByteArray {
        val response = client.get("$GMAIL_BASE/messages/$messageId/attachments/$attachmentId") {
            header("Authorization", "Bearer $accessToken")
        }
        val body = response.body<String>()
        if (!response.status.isSuccess()) {
            throw GmailApiException(
                response.status.value,
                "Gmail attachment download failed (${response.status}): $body",
            )
        }
        val data = gmailJson.parseToJsonElement(body).jsonObject["data"]
            ?.jsonPrimitive?.content
            ?: throw GmailApiException(0, "No 'data' field in attachment response")
        return Base64.getUrlDecoder().decode(data)
    }
}

// ── Message parsing ────────────────────────────────────────────────────────────

private fun parseMessage(json: JsonObject): GmailMessage {
    val id = json["id"]!!.jsonPrimitive.content
    val threadId = json["threadId"]!!.jsonPrimitive.content
    val snippet = json["snippet"]?.jsonPrimitive?.content ?: ""
    val labelIds = json["labelIds"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()

    val payload = json["payload"]!!.jsonObject
    val headers = payload["headers"]?.jsonArray ?: buildJsonArray {}

    fun headerValue(name: String): String =
        headers.firstOrNull {
            it.jsonObject["name"]?.jsonPrimitive?.content.equals(name, ignoreCase = true)
        }?.jsonObject?.get("value")?.jsonPrimitive?.content ?: ""

    // Recursive walk to collect body text parts and attachment metadata.
    val bodyParts = mutableListOf<Pair<String, String>>() // mimeType → decoded text
    val attachments = mutableListOf<GmailAttachment>()

    fun walkParts(part: JsonObject) {
        val mimeType = part["mimeType"]?.jsonPrimitive?.content ?: ""
        val filename = part["filename"]?.jsonPrimitive?.content ?: ""
        val body = part["body"]?.jsonObject
        val parts = part["parts"]?.jsonArray

        when {
            // Container type — recurse into children.
            parts != null -> parts.forEach { walkParts(it.jsonObject) }

            // Attachment (has a filename or a separate attachmentId to fetch).
            filename.isNotBlank() && body?.get("attachmentId")?.jsonPrimitive?.content != null -> {
                attachments += GmailAttachment(
                    attachmentId = body["attachmentId"]!!.jsonPrimitive.content,
                    filename = filename,
                    mimeType = mimeType,
                    size = body["size"]?.jsonPrimitive?.intOrNull ?: 0,
                )
            }

            // Inline text body.
            mimeType.startsWith("text/") -> {
                val data = body?.get("data")?.jsonPrimitive?.content
                if (data != null) {
                    bodyParts += mimeType to String(
                        Base64.getUrlDecoder().decode(data),
                        Charsets.UTF_8,
                    )
                }
            }
        }
    }
    walkParts(payload)

    val textBody = bodyParts.firstOrNull { it.first == "text/plain" }?.second ?: ""
    val htmlBody = bodyParts.firstOrNull { it.first == "text/html" }?.second

    return GmailMessage(
        id = id,
        threadId = threadId,
        subject = headerValue("Subject"),
        from = headerValue("From"),
        to = headerValue("To"),
        cc = headerValue("Cc"),
        date = headerValue("Date"),
        snippet = snippet,
        bodyText = textBody,
        bodyHtml = if (textBody.isBlank()) htmlBody else null,
        attachments = attachments,
        labelIds = labelIds,
    )
}

private fun defaultGmailClient(): HttpClient = HttpClient(OkHttp) {
    install(HttpTimeout) {
        requestTimeoutMillis = 30_000
        connectTimeoutMillis = 15_000
        socketTimeoutMillis = 30_000
    }
}
