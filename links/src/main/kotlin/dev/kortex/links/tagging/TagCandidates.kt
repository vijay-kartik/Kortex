package dev.kortex.links.tagging

import java.net.URI

/**
 * New tag names read straight off a page, best first. Unlike [TagSuggester], these are not
 * existing tags, so callers drop the ones the user already has.
 *
 * One pick from each source leads — a meta keyword, the last word of the URL slug (usually the
 * thing itself), then the site's name — so `curaahome.com/products/curaa-automatic-pepper-grinder`
 * with keyword "homeware" starts homeware, grinder, curaahome.
 */
fun tagCandidates(page: PageMetadata): List<String> {
    val keywords = page.keywords
        .map { it.trim().lowercase() }
        .filter { it.length in MIN_WORD_LENGTH..MAX_KEYWORD_LENGTH && it.count { c -> c == ' ' } <= 1 && it !in STOPWORDS }
    val uri = runCatching { URI(page.url.trim()) }.getOrNull()
    val slugWords = slugWords(uri?.path.orEmpty())
    val siteName = uri?.host.orEmpty()
        .removePrefix("www.")
        .split('.')
        .lastOrNull { isUsefulWord(it.lowercase()) && it.lowercase() !in HOST_NOISE }
        ?.lowercase()

    return (listOfNotNull(keywords.firstOrNull(), slugWords.firstOrNull(), siteName) + keywords.drop(1) + slugWords.drop(1))
        .distinct()
}

/** Words of the last path segment that has any, last word first: `/p/blue-steel-kettle` → kettle, steel, blue. */
private fun slugWords(path: String): List<String> =
    path.split('/')
        .asReversed()
        .map { segment ->
            segment.substringBeforeLast('.')
                .split('-', '_', '+', ' ')
                .map { it.lowercase() }
                .filter(::isUsefulWord)
        }
        .firstOrNull { it.isNotEmpty() }
        ?.asReversed()
        .orEmpty()

private fun isUsefulWord(word: String) =
    word.length >= MIN_WORD_LENGTH && word.all { it.isLetter() } && word !in STOPWORDS

private const val MIN_WORD_LENGTH = 3
private const val MAX_KEYWORD_LENGTH = 24

private val HOST_NOISE = setOf("com", "net", "org", "app", "dev", "shop", "store", "blog")

private val STOPWORDS = setOf(
    "the", "and", "for", "with", "from", "your", "you", "our", "new", "best", "buy", "sale", "online",
    "official", "free", "how", "what", "why", "shop", "store", "product", "products", "item", "items",
    "category", "categories", "collection", "collections", "detail", "details", "page", "pages", "index",
    "html", "htm", "php", "aspx", "amp", "blog", "post", "posts", "article", "articles", "news", "watch",
    "www", "ref",
)
