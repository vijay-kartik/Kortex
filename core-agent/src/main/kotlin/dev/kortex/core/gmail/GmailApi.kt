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
    /** The `text/plain` part; blank when the mail has none. */
    val bodyText: String,
    /** The `text/html` part, whether or not there is a plain-text one; null when the mail has none. */
    val bodyHtml: String?,
    val attachments: List<GmailAttachment>,
    val labelIds: List<String>,
    /** The `Message-ID` header: identifies this mail anywhere, not just in this mailbox. */
    val rfc822MessageId: String = "",
    /** When Gmail received it, in epoch milliseconds; null when it didn't say. */
    val internalDateMillis: Long? = null,
    /** Images the HTML body shows through `cid:` references, rather than from the web. */
    val inlineImages: List<GmailInlineImage> = emptyList(),
)

data class GmailAttachment(
    val attachmentId: String,
    val filename: String,
    val mimeType: String,
    val size: Int,
)

/**
 * An image carried in the mail for its HTML to show, named there as `cid:<contentId>`. Small ones
 * come inline as [data]; larger ones are fetched by [attachmentId] with [GmailApi.getAttachment].
 */
data class GmailInlineImage(
    /** The part's `Content-ID`, without its angle brackets. */
    val contentId: String,
    val mimeType: String,
    val attachmentId: String?,
    val data: ByteArray?,
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
     * Fetch a single message.
     *
     * [format] is Gmail's: `full` carries the body and attachments, `metadata` only the headers
     * named in [metadataHeaders], which is far less to send and to parse when a caller just
     * wants to list messages.
     *
     * @throws GmailApiException on non-2xx responses.
     */
    suspend fun getMessage(
        accessToken: String,
        messageId: String,
        format: String = FORMAT_FULL,
        metadataHeaders: List<String> = emptyList(),
    ): GmailMessage {
        val response = client.get("$GMAIL_BASE/messages/$messageId") {
            header("Authorization", "Bearer $accessToken")
            parameter("format", format)
            metadataHeaders.forEach { parameter("metadataHeaders", it) }
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

    companion object {
        /** The whole message: body, parts and attachment metadata. */
        const val FORMAT_FULL = "full"

        /** Headers only — whichever ones the caller names — plus the id, labels and dates. */
        const val FORMAT_METADATA = "metadata"
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
    val inlineImages = mutableListOf<GmailInlineImage>()

    fun walkParts(part: JsonObject) {
        val mimeType = part["mimeType"]?.jsonPrimitive?.content ?: ""
        val filename = part["filename"]?.jsonPrimitive?.content ?: ""
        val body = part["body"]?.jsonObject
        val parts = part["parts"]?.jsonArray

        // An image the HTML shows by its Content-ID. Noted on the side: whether it is also listed
        // as an attachment below is unchanged.
        val contentId = part["headers"]?.jsonArray?.firstOrNull {
            it.jsonObject["name"]?.jsonPrimitive?.content.equals("Content-ID", ignoreCase = true)
        }?.jsonObject?.get("value")?.jsonPrimitive?.content?.trim()?.removeSurrounding("<", ">")
        if (!contentId.isNullOrBlank() && mimeType.startsWith("image/") && body != null) {
            inlineImages += GmailInlineImage(
                contentId = contentId,
                mimeType = mimeType,
                attachmentId = body["attachmentId"]?.jsonPrimitive?.content,
                data = body["data"]?.jsonPrimitive?.content?.let { Base64.getUrlDecoder().decode(it) },
            )
        }

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
        bodyHtml = htmlBody,
        attachments = attachments,
        labelIds = labelIds,
        // Angle brackets are part of the header's syntax, not of the id itself.
        rfc822MessageId = headerValue("Message-ID").trim().removeSurrounding("<", ">"),
        internalDateMillis = json["internalDate"]?.jsonPrimitive?.content?.toLongOrNull(),
        inlineImages = inlineImages,
    )
}

private fun defaultGmailClient(): HttpClient = HttpClient(OkHttp) {
    install(HttpTimeout) {
        requestTimeoutMillis = 30_000
        connectTimeoutMillis = 15_000
        socketTimeoutMillis = 30_000
    }
}
