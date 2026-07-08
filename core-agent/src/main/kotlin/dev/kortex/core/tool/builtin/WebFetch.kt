package dev.kortex.core.tool.builtin

import dev.kortex.core.tool.RiskLevel
import dev.kortex.core.tool.Tool
import dev.kortex.core.tool.ToolResult
import dev.kortex.core.tool.int
import dev.kortex.core.tool.string
import dev.kortex.core.tool.tool
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.ContentType
import io.ktor.http.contentLength
import io.ktor.http.contentType

/**
 * Follows through on a web_search result (pattern 5). Search only returns short snippets;
 * when a result points at a page with the real answer — "full list at…", a live/real-time
 * page, or a snippet that's obviously cut off — the agent needs to actually open the page
 * and read it, instead of guessing or telling the user to click the link themselves.
 */
fun openUrlTool(client: HttpClient = defaultHttpClient()): Tool = tool(
    name = "open_url",
    description = "Fetch a web page by URL and return its readable text content. Use this " +
        "after web_search whenever a result's snippet is incomplete, references a page with " +
        "more detail (e.g. \"full list at...\"), or is a live/real-time page. Don't tell the " +
        "user to visit a link themselves if you can open it and read the answer directly.",
) {
    param("url", "string", "The full URL to fetch, typically from a web_search result.")
    param("max_chars", "integer", "Max characters of extracted text to return (default 4000).", required = false)
    risk(RiskLevel.LOW)
    execute { args ->
        val url = args.string("url")
        val maxChars = args.int("max_chars", default = 4000).coerceIn(500, 12_000)
        runCatching { fetchReadableText(client, url) }
            .fold(
                onSuccess = { text ->
                    if (text.isBlank()) {
                        ToolResult(true, "Fetched \"$url\" but found no readable text (page may be JS-rendered).")
                    } else {
                        ToolResult(true, text.take(maxChars))
                    }
                },
                onFailure = { ToolResult(false, "Could not fetch \"$url\": ${it.message}") },
            )
    }
}

private const val MAX_DOWNLOAD_BYTES = 5 * 1024 * 1024L // 5MB — pages larger than this are refused up front.

private suspend fun fetchReadableText(client: HttpClient, url: String): String {
    val response = client.get(url) {
        header("User-Agent", USER_AGENT)
        header("Accept-Language", "en-US,en;q=0.9")
    }

    val contentType = response.contentType()
    require(contentType == null || contentType.match(ContentType.Text.Html) || contentType.match(ContentType.Text.Plain)) {
        "unsupported content type '$contentType' (expected HTML or plain text)"
    }
    val contentLength = response.contentLength()
    require(contentLength == null || contentLength <= MAX_DOWNLOAD_BYTES) {
        "page too large (${contentLength}B, limit ${MAX_DOWNLOAD_BYTES}B)"
    }

    return extractReadableText(response.body())
}

// Strip script/style/comments, then tags, then collapse whitespace — same lightweight,
// dependency-free approach WebSearch.kt uses to parse DDG's HTML (no HTML parser library).
internal fun extractReadableText(html: String): String {
    val withoutNoise = html
        .replace(Regex("(?is)<script.*?</script>"), " ")
        .replace(Regex("(?is)<style.*?</style>"), " ")
        .replace(Regex("(?is)<!--.*?-->"), " ")
    val bodyOnly = Regex("(?is)<body[^>]*>(.*)</body>").find(withoutNoise)?.groupValues?.get(1) ?: withoutNoise

    return bodyOnly
        .replace(Regex("(?is)</?(br|p|div|li|h[1-6]|tr)[^>]*>"), "\n")
        .replace(Regex("<[^>]+>"), " ")
        .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&#x27;", "'").replace("&#39;", "'")
        .replace(Regex("[ \\t]+"), " ")
        .replace(Regex("[ \\t]*\\n[ \\t]*"), "\n")
        .replace(Regex("\\n{3,}"), "\n\n")
        .trim()
}
