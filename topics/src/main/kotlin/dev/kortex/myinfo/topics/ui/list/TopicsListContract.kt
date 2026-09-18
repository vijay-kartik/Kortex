package dev.kortex.myinfo.topics.ui.list

import dev.kortex.myinfo.topics.domain.model.TopicOverview
import dev.kortex.myinfo.topics.domain.model.TopicSort
import dev.kortex.myinfo.topics.domain.model.TopicSuggestion
import dev.kortex.myinfo.topics.domain.model.sortedFor

/** Topics list (Figma: Topics 1a) and, before the first topic exists, the first-run screen (1g). */
data class TopicsListState(
    /** Before the first read, so the first-run screen doesn't flash for users who have topics. */
    val loading: Boolean = true,
    /** Every topic, unordered. A topic in its undo window is still here, shown as an undo row. */
    val topics: List<TopicOverview> = emptyList(),
    val sort: TopicSort = TopicSort.Recent,
    val suggestions: List<TopicSuggestion> = emptyList(),
    val pendingDeletion: PendingTopicDeletion? = null,
    /** The topic whose options tray the user opened. Read [openOptionsTopicId] instead. */
    val optionsTopicId: Long? = null,
) {
    val firstRun: Boolean = !loading && topics.isEmpty()

    val visibleTopics: List<TopicOverview> = topics.sortedFor(sort)

    // Counts leave out the topic in its undo window.
    private val kept = topics.filterNot { it.topic.id == pendingDeletion?.topicId }
    val topicCount: Int = kept.size
    val itemCount: Int = kept.sumOf { it.itemCount }
    val pinnedCount: Int = kept.count { it.topic.pinned }

    /** The tray closes for good when its topic is filtered out or deleted. */
    val openOptionsTopicId: Long? = optionsTopicId?.takeIf { id ->
        id != pendingDeletion?.topicId && visibleTopics.any { it.topic.id == id }
    }
}

/** A deleted topic that stays restorable until [deadlineMillis] (wall clock). */
data class PendingTopicDeletion(
    val topicId: Long,
    val startedAtMillis: Long,
    val deadlineMillis: Long,
)

sealed interface TopicsListIntent {
    data class SelectSort(val sort: TopicSort) : TopicsListIntent
    data class OpenTopic(val topicId: Long) : TopicsListIntent
    data class ShowOptions(val topicId: Long) : TopicsListIntent
    data object HideOptions : TopicsListIntent
    data class SetPinned(val topicId: Long, val pinned: Boolean) : TopicsListIntent
    data class Delete(val topicId: Long) : TopicsListIntent
    data object UndoDelete : TopicsListIntent
    data object CreateTopic : TopicsListIntent
    data object Search : TopicsListIntent
    data class AcceptSuggestion(val suggestion: TopicSuggestion) : TopicsListIntent
}

sealed interface TopicsListEffect {
    data class OpenTopic(val topicId: Long) : TopicsListEffect
    data object OpenNewTopic : TopicsListEffect

    /** Search across every topic (Figma: Topics 1f); full-screen, so the host shows it. */
    data object OpenSearch : TopicsListEffect
}
