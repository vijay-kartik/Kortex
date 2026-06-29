package dev.kortex.core.tool.builtin

import dev.kortex.core.tool.RiskLevel
import dev.kortex.core.tool.Tool
import dev.kortex.core.tool.ToolResult
import dev.kortex.core.tool.int
import dev.kortex.core.tool.string
import dev.kortex.core.tool.tool
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * A real web-search tool (pattern 5) backed by DuckDuckGo's keyless HTML endpoint, so it
 * searches the actual web index — including brand-new pages Wikipedia wouldn't have yet.
 *
 * Note: this scrapes HTML, so it can be rate-limited/blocked from datacenter IPs (it is
 * more reliable from a real device). The backend is swappable (Brave/Tavily/Bing) later
 * without changing the agent — only this factory changes.
 */
fun webSearchTool(client: HttpClient = defaultHttpClient()): Tool = tool(
    name = "web_search",
    description = "Search the web (DuckDuckGo) for current or factual information and " +
        "return the top results as title, snippet, and URL. Use when the answer may be " +
        "recent or outside your training data.",
) {
    param("query", "string", "The search query.")
    param("limit", "integer", "Max results to return (1-8, default 5).", required = false)
    risk(RiskLevel.LOW)
    execute { args ->
        val query = args.string("query")
        val limit = args.int("limit", default = 5).coerceIn(1, 8)
        runCatching { search(client, query, limit) }
            .fold(
                onSuccess = { results ->
                    if (results.isEmpty()) {
                        ToolResult(true, "No results found for \"$query\". The query may be too " +
                            "specific, or the search endpoint may have throttled the request.")
                    } else {
                        ToolResult(true, results.joinToString("\n\n") {
                            "• ${it.title}\n  ${it.snippet}\n  ${it.url}"
                        })
                    }
                },
                onFailure = { ToolResult(false, "Search failed: ${it.message}") },
            )
    }
}

internal data class SearchResult(val title: String, val snippet: String, val url: String)

private const val USER_AGENT =
    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/124.0 Mobile Safari/537.36"

private suspend fun search(client: HttpClient, query: String, limit: Int): List<SearchResult> {
    val html: String = client.get("https://html.duckduckgo.com/html/") {
        parameter("q", query)
        header("User-Agent", USER_AGENT)
        header("Accept-Language", "en-US,en;q=0.9")
    }.body()

    return parseResults(html).take(limit)
}

// DDG HTML result anchors: class="result__a" (title+link) and class="result__snippet".
private val titleRegex =
    Regex("""<a[^>]*class="result__a"[^>]*href="([^"]+)"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
private val snippetRegex =
    Regex("""<a[^>]*class="result__snippet"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)

internal fun parseResults(html: String): List<SearchResult> {
    val titles = titleRegex.findAll(html).toList()
    val snippets = snippetRegex.findAll(html).map { it.groupValues[1].cleanHtml() }.toList()

    return titles.mapIndexed { i, m ->
        SearchResult(
            title = m.groupValues[2].cleanHtml(),
            snippet = snippets.getOrElse(i) { "" },
            url = decodeDdgLink(m.groupValues[1]),
        )
    }.filter { it.title.isNotBlank() }
}

/** DDG links are redirects like `//duckduckgo.com/l/?uddg=<encoded-real-url>&...`. */
private fun decodeDdgLink(href: String): String {
    val uddg = Regex("""[?&]uddg=([^&]+)""").find(href)?.groupValues?.get(1)
    return if (uddg != null) {
        runCatching { URLDecoder.decode(uddg, StandardCharsets.UTF_8.name()) }.getOrDefault(href)
    } else {
        if (href.startsWith("//")) "https:$href" else href
    }
}

private fun String.cleanHtml(): String =
    replace(Regex("<[^>]+>"), "")
        .replace("&quot;", "\"").replace("&#x27;", "'").replace("&#39;", "'")
        .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ")
        .replace("&mdash;", "—").replace("&ndash;", "–").replace("&hellip;", "…")
        .replace(Regex("\\s+"), " ")
        .trim()

internal fun defaultHttpClient(): HttpClient = HttpClient(OkHttp) {
    install(HttpTimeout) {
        requestTimeoutMillis = 30_000
        connectTimeoutMillis = 15_000
        socketTimeoutMillis = 30_000
    }
}
