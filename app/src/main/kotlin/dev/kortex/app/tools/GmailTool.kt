package dev.kortex.app.tools

import android.content.Context
import dev.kortex.core.gmail.GmailApi
import dev.kortex.core.gmail.GmailApiException
import dev.kortex.core.gmail.GmailAttachment
import dev.kortex.core.gmail.GmailMessage
import dev.kortex.core.tool.RiskLevel
import dev.kortex.core.tool.Tool
import dev.kortex.core.tool.ToolResult
import dev.kortex.core.tool.int
import dev.kortex.core.tool.string
import dev.kortex.core.tool.stringOrNull
import dev.kortex.core.tool.tool
import java.io.File

/**
 * Tool that searches and reads emails from the user's Gmail account via the
 * Gmail REST API. The LLM translates the user's natural-language request into
 * Gmail search operators and calls this tool.
 *
 * Attachments are downloaded to the app's cache directory and their file paths
 * are included in the response so the agent (or user) can reference them.
 *
 * @param context         Android context for file I/O (cache dir).
 * @param tokenProvider   returns a valid OAuth2 access token, or null if the
 *                        user has not connected their Google account yet.
 * @param gmailApi        the [GmailApi] HTTP client instance.
 */
fun gmailTool(
    context: Context,
    tokenProvider: suspend () -> String?,
    gmailApi: GmailApi = GmailApi(),
): Tool = tool(
    name = "gmail_search",
    description = "Search and read emails from the user's Gmail account. " +
        "Returns full email content including subject, sender, date, body text, " +
        "and downloads any attachments. Use Gmail search syntax for the query.",
) {
    param(
        "query", "string",
        "Gmail search query. Use Gmail search operators: " +
            "'from:', 'to:', 'subject:', 'is:unread', 'is:starred', 'has:attachment', " +
            "'after:YYYY/MM/DD', 'before:YYYY/MM/DD', 'label:', 'filename:', 'in:inbox'. " +
            "Combine with spaces (AND) or 'OR'. " +
            "Examples: 'is:unread in:inbox', 'from:boss@company.com subject:review', " +
            "'has:attachment filename:pdf after:2025/01/01'.",
    )
    param(
        "max_results", "integer",
        "Maximum number of emails to fetch and return (1–10, default 5).",
        required = false,
    )
    param(
        "include_body", "string",
        "'yes' (default) to include the full email body, 'no' for headers + snippet only.",
        required = false,
    )
    param(
        "download_attachments", "string",
        "'yes' (default) to download attachments to local storage, 'no' to skip.",
        required = false,
    )

    risk(RiskLevel.MEDIUM)

    promptHint(
        "Reads the user's real Gmail. Always use Gmail search operators to build the " +
            "query — the raw user question should NOT be passed verbatim. " +
            "If the user asks 'do I have new emails?', use 'is:unread in:inbox'. " +
            "Attachments are automatically downloaded and their local file paths returned.",
    )

    execute { args ->
        val token = tokenProvider()
            ?: return@execute ToolResult(
                false,
                "Gmail is not connected. Ask the user to connect their Google account " +
                    "in Settings \u2192 MCP Settings \u2192 Gmail section.",
            )

        val query = args.string("query")
        val maxResults = args.int("max_results", default = 5).coerceIn(1, 10)
        val includeBody = args.stringOrNull("include_body")?.lowercase() != "no"
        val downloadAttachments = args.stringOrNull("download_attachments")?.lowercase() != "no"

        runCatching {
            // 1. List matching message IDs.
            val messageIds = gmailApi.listMessages(token, query, maxResults)
            if (messageIds.isEmpty()) {
                return@execute ToolResult(true, "No emails found matching: \"$query\"")
            }

            // 2. Fetch full content for each message.
            val messages = messageIds.map { gmailApi.getMessage(token, it) }

            // 3. Format the response, downloading attachments as needed.
            val attachmentDir = File(context.cacheDir, "gmail_attachments").apply { mkdirs() }

            val result = buildString {
                appendLine("Found ${messages.size} email(s) matching \"$query\":")
                appendLine()

                for ((i, msg) in messages.withIndex()) {
                    appendLine("\u2550\u2550\u2550 Email ${i + 1} of ${messages.size} \u2550\u2550\u2550")
                    appendLine("Subject: ${msg.subject}")
                    appendLine("From:    ${msg.from}")
                    appendLine("To:      ${msg.to}")
                    if (msg.cc.isNotBlank()) appendLine("Cc:      ${msg.cc}")
                    appendLine("Date:    ${msg.date}")
                    appendLine("Labels:  ${msg.labelIds.joinToString(", ")}")
                    appendLine("ID:      ${msg.id}")
                    appendLine()

                    if (includeBody) {
                        val body = msg.bodyText.ifBlank {
                            // Fall back to HTML with tags stripped.
                            msg.bodyHtml?.stripHtml() ?: msg.snippet
                        }
                        appendLine("Body:")
                        val trimmed = body.take(4000)
                        appendLine(trimmed)
                        if (body.length > 4000) appendLine("\u2026 [body truncated at 4 000 chars]")
                    } else {
                        appendLine("Snippet: ${msg.snippet}")
                    }

                    if (msg.attachments.isNotEmpty()) {
                        appendLine()
                        appendLine("Attachments (${msg.attachments.size}):")
                        for (att in msg.attachments) {
                            val sizeStr = formatBytes(att.size)
                            if (downloadAttachments) {
                                val saved = downloadAttachment(
                                    gmailApi, token, msg.id, att, attachmentDir,
                                )
                                appendLine("  \uD83D\uDCCE ${att.filename} ($sizeStr, ${att.mimeType})")
                                if (saved != null) {
                                    appendLine("     Saved to: ${saved.absolutePath}")
                                    // For small text-based files, include content inline.
                                    if (att.size < 8_000 && att.mimeType.startsWith("text/")) {
                                        appendLine("     Content:")
                                        appendLine(saved.readText().take(4000))
                                    }
                                } else {
                                    appendLine("     (download failed)")
                                }
                            } else {
                                appendLine("  \uD83D\uDCCE ${att.filename} ($sizeStr, ${att.mimeType}) [not downloaded]")
                            }
                        }
                    }
                    appendLine()
                }
            }
            ToolResult(true, result)
        }.getOrElse { err ->
            val hint = when {
                err is GmailApiException && err.statusCode == 401 ->
                    "The Gmail access token has expired. Ask the user to reconnect their Google account in Settings."
                err is GmailApiException && err.statusCode == 403 ->
                    "Gmail API access is forbidden. The OAuth scope may not include gmail.readonly."
                else -> "Gmail search failed: ${err.message}"
            }
            ToolResult(false, hint)
        }
    }
}

// ── Helpers ──────────────────────────────────────────────────────────────────────

private suspend fun downloadAttachment(
    api: GmailApi,
    token: String,
    messageId: String,
    att: GmailAttachment,
    dir: File,
): File? = runCatching {
    val bytes = api.getAttachment(token, messageId, att.attachmentId)
    val safeFilename = att.filename.replace(Regex("[^a-zA-Z0-9._-]"), "_")
    val target = File(dir, "${messageId.take(12)}_$safeFilename")
    target.writeBytes(bytes)
    target
}.getOrNull()

private fun formatBytes(bytes: Int): String = when {
    bytes < 1_024 -> "$bytes B"
    bytes < 1_024 * 1_024 -> "${bytes / 1_024} KB"
    else -> "${"%,.1f".format(bytes / (1_024.0 * 1_024.0))} MB"
}

/** Crude HTML → plain-text: strips tags, decodes common entities, collapses whitespace. */
private fun String.stripHtml(): String =
    replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
        .replace(Regex("<[^>]+>"), " ")
        .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ")
        .replace(Regex("[ \\t]+"), " ")
        .replace(Regex("\\n{3,}"), "\n\n")
        .trim()
