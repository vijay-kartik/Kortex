package dev.kortex.myinfo.topics.domain.model

/** Which items a search looks at (Figma: Topics 1f). Topic names are always searched. */
enum class SearchScope(val types: Set<ItemType>) {
    Everything(ItemType.entries.toSet()),
    Notes(setOf(ItemType.Note)),
    Links(setOf(ItemType.Link, ItemType.Article, ItemType.Video)),
    Files(setOf(ItemType.Doc, ItemType.Image)),
    Bills(setOf(ItemType.Bill)),
}

/** A piece of text with the query's occurrences marked, for the UI to pick out. */
data class Highlighted(val text: String, val matches: List<IntRange>) {
    val matched: Boolean get() = matches.isNotEmpty()

    companion object {
        val Empty = Highlighted("", emptyList())
    }
}

/** One item a search turned up. */
data class SearchHit(
    val item: TopicItem,
    /** What the item is called, or its first line.  */
    val title: Highlighted,
    /** The line under it — a link's address, a bill's currency — or null when there is none. */
    val detail: Highlighted?,
)

/** One topic's share of the results (Figma: Topics 1f groups results by topic). */
data class TopicResults(
    val topic: Topic,
    val name: Highlighted,
    val hits: List<SearchHit>,
) {
    /** The topic's own name matched, so it is worth offering even with no matching items. */
    val nameMatched: Boolean get() = name.matched
}

data class SearchResults(
    val query: String,
    val scope: SearchScope,
    val groups: List<TopicResults>,
) {
    val itemCount: Int = groups.sumOf { it.hits.size }
    val topicCount: Int = groups.size
    val empty: Boolean = groups.isEmpty()

    companion object {
        /** Before anything has been typed. */
        fun none(scope: SearchScope = SearchScope.Everything) = SearchResults("", scope, emptyList())
    }
}

/**
 * Everything search reads, held in memory: the topics and their items, links already resolved.
 * The corpus is what the topics list already loads, so searching costs no extra reads and a
 * keystroke re-searches without touching the database.
 */
data class SearchCorpus(
    val topics: List<Topic>,
    val itemsByTopic: Map<Long, List<TopicItem>>,
) {
    /**
     * Topics and items matching every word of [query], ignoring case. A topic whose name matches
     * is offered even when none of its items do; [scope] narrows the items, never the names.
     * Groups come name-matches first, then busiest, then most recently changed.
     */
    fun search(query: String, scope: SearchScope = SearchScope.Everything): SearchResults {
        val terms = query.trim().split(WHITESPACE).filter { it.isNotEmpty() }
        if (terms.isEmpty()) return SearchResults.none(scope)

        val groups = topics.mapNotNull { topic ->
            val name = highlight(topic.name, terms)
            val hits = itemsByTopic[topic.id].orEmpty()
                .filter { it.type in scope.types }
                .mapNotNull { it.hit(terms) }
            if (!name.matched && hits.isEmpty()) null else TopicResults(topic, name, hits)
        }
        return SearchResults(
            query = query.trim(),
            scope = scope,
            groups = groups.sortedWith(
                compareByDescending<TopicResults> { it.nameMatched }
                    .thenByDescending { it.hits.size }
                    .thenByDescending { it.topic.updatedAtMillis },
            ),
        )
    }

    companion object {
        val Empty = SearchCorpus(emptyList(), emptyMap())
        private val WHITESPACE = Regex("""\s+""")
    }
}

/** Null when the item doesn't hold every term; every term has to appear somewhere in it. */
private fun TopicItem.hit(terms: List<String>): SearchHit? {
    val (title, detail) = searchableText()
    val haystack = listOfNotNull(title, detail).joinToString(" ")
    if (!terms.all { haystack.contains(it, ignoreCase = true) }) return null
    return SearchHit(
        item = this,
        title = highlight(title, terms),
        detail = detail?.let { highlight(it, terms) },
    )
}

/** What the item reads as in a result row: its line, and the smaller line under it. */
private fun TopicItem.searchableText(): Pair<String, String?> = when (this) {
    is TopicItem.Note -> text to null
    is TopicItem.Link -> link.title.ifBlank { link.url } to link.url
    is TopicItem.Article -> link.title.ifBlank { link.url } to link.url
    is TopicItem.Video -> link.title.ifBlank { link.url } to link.url
    is TopicItem.Doc -> title to null
    // An image with no caption still turns up when its topic's name matches.
    is TopicItem.Image -> (caption ?: "") to null
    is TopicItem.Bill -> title to amount.currency
}

/** Every occurrence of every term in [text], merged where they overlap, left to right. */
internal fun highlight(text: String, terms: List<String>): Highlighted {
    if (text.isEmpty()) return Highlighted.Empty
    val found = mutableListOf<IntRange>()
    terms.forEach { term ->
        var from = 0
        while (from <= text.length - term.length) {
            val at = text.indexOf(term, from, ignoreCase = true)
            if (at < 0) break
            found += at until at + term.length
            from = at + term.length
        }
    }
    return Highlighted(text, found.merged())
}

/**
 * Ranges that overlap or meet with nothing between them become one, so the UI never draws a
 * marker twice. Words with a space between them stay separate: the space isn't part of either.
 */
private fun List<IntRange>.merged(): List<IntRange> {
    if (size < 2) return this
    val sorted = sortedBy { it.first }
    val merged = mutableListOf(sorted.first())
    sorted.drop(1).forEach { range ->
        val last = merged.last()
        if (range.first <= last.last + 1) {
            merged[merged.lastIndex] = last.first..maxOf(last.last, range.last)
        } else {
            merged += range
        }
    }
    return merged
}
