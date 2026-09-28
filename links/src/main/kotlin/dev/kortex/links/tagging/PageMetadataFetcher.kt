package dev.kortex.links.tagging

import dagger.Reusable
import dev.kortex.links.domain.model.PageMetadata
import dev.kortex.links.domain.port.PageReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.IOException
import javax.inject.Inject

@Reusable
class PageMetadataFetcher @Inject constructor() : PageReader {

    override suspend fun read(url: String): PageMetadata = withContext(Dispatchers.IO) {
        val document = try {
            Jsoup.connect(url)
                .userAgent(FETCH_USER_AGENT)
                .timeout(FETCH_TIMEOUT_MS)
                .maxBodySize(MAX_BODY_BYTES)
                .get()
        } catch (e: IOException) {
            return@withContext PageMetadata(url)
        } catch (e: IllegalArgumentException) {
            return@withContext PageMetadata(url)
        }

        PageMetadata(
            url = url,
            title = document.meta("og:title", "twitter:title") ?: document.title().trim().ifEmpty { null },
            description = document.meta("og:description", "description", "twitter:description"),
            siteName = document.meta("og:site_name"),
            imageUrl = document.metaUrl("og:image", "og:image:secure_url", "og:image:url", "twitter:image", "twitter:image:src"),
            keywords = document.meta("keywords")
                ?.split(',')
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?.take(MAX_KEYWORDS)
                .orEmpty(),
            bodySnippet = document.select("p").eachText()
                .joinToString(" ")
                .take(BODY_SNIPPET_CHARS)
                .ifBlank { null },
        )
    }

    private fun Document.meta(vararg keys: String): String? = keys.firstNotNullOfOrNull { key ->
        selectFirst("meta[property=\"$key\"], meta[name=\"$key\"]")
            ?.attr("content")
            ?.trim()
            ?.ifEmpty { null }
    }

    /** Like [meta], but resolves relative addresses against the page and keeps only http(s) ones. */
    private fun Document.metaUrl(vararg keys: String): String? = keys.firstNotNullOfOrNull { key ->
        selectFirst("meta[property=\"$key\"], meta[name=\"$key\"]")
            ?.absUrl("content")
            ?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
    }

    private companion object {
        // Tags live in <head>; a truncated body is fine for Jsoup and for the snippet.
        const val MAX_BODY_BYTES = 512_000
        const val MAX_KEYWORDS = 10
        // Sentence encoders dilute on long input; the lead paragraphs carry the topic.
        const val BODY_SNIPPET_CHARS = 600
    }
}

internal const val FETCH_USER_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"

/** Per-request budget, shared by the page fetch and the share-image download. */
internal const val FETCH_TIMEOUT_MS = 8_000
