package dev.kortex.myinfo.topics.ui.detail

import dev.kortex.myinfo.topics.domain.model.ItemType
import dev.kortex.myinfo.topics.domain.model.TopicDetail
import dev.kortex.myinfo.topics.domain.model.TopicItem
import dev.kortex.myinfo.topics.ui.common.SectionOrder

/** One topic's feed (Figma: Topics 1b), newest first. */
data class TopicDetailState(
    /** Null before the first read. */
    val detail: TopicDetail? = null,
    /** Null shows every item. */
    val filter: ItemType? = null,
    val confirmingDelete: Boolean = false,
    /** The quick-capture sheet is open over the feed. */
    val capturing: Boolean = false,
) {
    /** One chip per visible section: most items first, then section order; empty sections last. */
    val filters: List<TypeFilter> = detail?.let { d ->
        d.visibleSections
            .map { TypeFilter(it, d.counts[it] ?: 0) }
            .sortedWith(compareByDescending<TypeFilter> { it.count }.thenBy { SectionOrder.indexOf(it.type) })
    }.orEmpty()

    /** A filter whose section has gone falls back to everything. */
    val activeFilter: ItemType? = filter?.takeIf { type -> filters.any { it.type == type } }

    val items: List<TopicItem> = detail?.items.orEmpty().filter { activeFilter == null || it.type == activeFilter }
}

data class TypeFilter(val type: ItemType, val count: Int)

sealed interface TopicDetailIntent {
    data class SelectFilter(val type: ItemType?) : TopicDetailIntent
    data class OpenItem(val item: TopicItem) : TopicDetailIntent
    data object Add : TopicDetailIntent
    data object CloseCapture : TopicDetailIntent

    /** The capture sheet saved an item, into this topic or the one the user picked instead. */
    data class Captured(val topicId: Long, val topicName: String) : TopicDetailIntent
    data object Share : TopicDetailIntent
    data class SetPinned(val pinned: Boolean) : TopicDetailIntent
    data object AskDelete : TopicDetailIntent
    data object CancelDelete : TopicDetailIntent
    data object ConfirmDelete : TopicDetailIntent
}

sealed interface TopicDetailEffect {
    data class OpenUrl(val url: String) : TopicDetailEffect
    data class ShareText(val subject: String, val text: String) : TopicDetailEffect
    data class ShowMessage(val text: String) : TopicDetailEffect

    /** The topic is gone: deleted here, or elsewhere while open. */
    data object Close : TopicDetailEffect
}
