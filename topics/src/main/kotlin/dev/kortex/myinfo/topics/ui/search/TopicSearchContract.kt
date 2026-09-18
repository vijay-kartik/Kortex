package dev.kortex.myinfo.topics.ui.search

import dev.kortex.myinfo.topics.domain.model.SearchResults
import dev.kortex.myinfo.topics.domain.model.SearchScope

/**
 * Search across every topic (Figma: Topics 1f). The query lives in the screen's field; the
 * ViewModel hears each change, waits for typing to pause and re-runs the search.
 */
data class TopicSearchState(
    /** The query the results belong to — the field can be ahead of it while typing. */
    val query: String = "",
    val scope: SearchScope = SearchScope.Everything,
    val results: SearchResults = SearchResults.none(),
    /** Typing has moved on and the results haven't caught up yet. */
    val searching: Boolean = false,
    /** Nothing has been typed, so the screen offers what to look for rather than "no results". */
    val idle: Boolean = true,
) {
    val scopes: List<SearchScope> = SearchScope.entries

    /** A search ran and turned up nothing. */
    val noResults: Boolean = !idle && !searching && results.empty
}

sealed interface TopicSearchIntent {
    data class QueryChanged(val query: String) : TopicSearchIntent
    data class SelectScope(val scope: SearchScope) : TopicSearchIntent
    data class OpenTopic(val topicId: Long) : TopicSearchIntent
}

sealed interface TopicSearchEffect {
    /** Both a topic row and an item row go to the topic; the item is in its feed. */
    data class OpenTopic(val topicId: Long) : TopicSearchEffect
}
