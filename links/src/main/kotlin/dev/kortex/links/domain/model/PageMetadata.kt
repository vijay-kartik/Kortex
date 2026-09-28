package dev.kortex.links.domain.model

import java.net.URI

/** What a page says about itself. Every field except [url] is null/empty when the page couldn't be fetched. */
data class PageMetadata(
    val url: String,
    val title: String? = null,
    val description: String? = null,
    val siteName: String? = null,
    /** Absolute address of the page's share image (og:image / twitter:image), if it names one. */
    val imageUrl: String? = null,
    val keywords: List<String> = emptyList(),
    val bodySnippet: String? = null,
) {
    /**
     * Text handed to the embedder, most descriptive fields first. URL words are always
     * included so an unreachable page (offline, paywall, non-HTML) still carries some signal.
     */
    fun toEmbeddingText(): String = listOfNotNull(
        title,
        description,
        siteName,
        keywords.takeIf { it.isNotEmpty() }?.joinToString(", "),
        urlWords(url).takeIf { it.isNotEmpty() }?.joinToString(" "),
        bodySnippet,
    ).joinToString("\n")
}

/** Human-meaningful words from the host and path, e.g. `github.com/foo/llama-cpp` → github, foo, llama, cpp. */
internal fun urlWords(url: String): List<String> {
    val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return emptyList()
    val hostWords = uri.host.orEmpty().removePrefix("www.").substringBeforeLast('.').split('.')
    val pathWords = uri.path.orEmpty().split('/', '-', '_', '.', '+')
    return (hostWords + pathWords)
        .map { it.lowercase() }
        // Drop ids, hashes and file extensions-sized noise.
        .filter { word -> word.length > 1 && word.count { it.isDigit() } <= word.length / 3 }
        .distinct()
}
