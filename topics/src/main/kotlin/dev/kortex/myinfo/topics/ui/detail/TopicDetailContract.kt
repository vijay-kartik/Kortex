package dev.kortex.myinfo.topics.ui.detail

import dev.kortex.myinfo.topics.domain.model.FeedSection
import dev.kortex.myinfo.topics.domain.model.ItemType
import dev.kortex.myinfo.topics.domain.model.StoredFile
import dev.kortex.myinfo.topics.domain.model.TopicDetail
import dev.kortex.myinfo.topics.domain.model.TopicFeed
import dev.kortex.myinfo.topics.domain.model.TopicItem
import dev.kortex.myinfo.topics.domain.model.TopicSummary
import dev.kortex.myinfo.topics.domain.model.TopicViewMode
import dev.kortex.myinfo.topics.ui.common.SectionOrder
import dev.kortex.myinfo.topics.ui.common.TopicChoice

/** One topic's feed (Figma: Topics 1b, 1c, 1e), newest first. */
data class TopicDetailState(
    /** Null before the first read. */
    val detail: TopicDetail? = null,
    /** Null shows every item. */
    val filter: ItemType? = null,
    /** How the feed is cut up (Figma: Topics 1c). */
    val mode: TopicViewMode = TopicViewMode.Feed,
    /** Items the user has picked out (Figma: Topics 1e). Read [selection] instead. */
    val selected: Set<Long> = emptySet(),
    /** The other topics the selection could move to, most recently changed first. */
    val moveTargets: List<TopicChoice> = emptyList(),
    /** The "move to" picker is open over the feed. */
    val movingSelection: Boolean = false,
    val confirmingDelete: Boolean = false,
    val confirmingSelectionDelete: Boolean = false,
    /** The quick-capture sheet is open over the feed. */
    val capturing: Boolean = false,
    /** When the feed was last arranged, for its ages and date groups. */
    val nowMillis: Long = 0,
    /** The last summary written for this topic, current or not (Figma: Topics 1b). */
    val summary: TopicSummary? = null,
    /** Fingerprint of the topic as it is now; a summary written from another one is out of date. */
    val currentFingerprint: String? = null,
    /** The agent is reading the topic. */
    val summarizing: Boolean = false,
    /** Why the last attempt failed, until the next one starts. */
    val summaryError: String? = null,
) {
    /** The card shows once there is something to summarise, or a summary to show. */
    val showSummaryCard: Boolean = detail != null && (detail.items.isNotEmpty() || summary != null)

    /** The topic changed after the summary was written. */
    val summaryStale: Boolean =
        summary != null && currentFingerprint != null && summary.fingerprint != currentFingerprint

    /** Summarise, Refresh and Try again all ask for the same thing; none of them while it is running. */
    val canSummarize: Boolean = !summarizing && detail != null && detail.items.isNotEmpty()

    /** One chip per visible section: most items first, then section order; empty sections last. */
    val filters: List<TypeFilter> = detail?.let { d ->
        d.visibleSections
            .map { TypeFilter(it, d.counts[it] ?: 0) }
            .sortedWith(compareByDescending<TypeFilter> { it.count }.thenBy { SectionOrder.indexOf(it.type) })
    }.orEmpty()

    /** A filter whose section has gone falls back to everything. */
    val activeFilter: ItemType? = filter?.takeIf { type -> filters.any { it.type == type } }

    val items: List<TopicItem> = detail?.items.orEmpty().filter { activeFilter == null || it.type == activeFilter }

    val sections: List<FeedSection> = TopicFeed.sections(items, mode, nowMillis)

    /** Only items still on screen: one hidden by a filter, or deleted elsewhere, drops out. */
    val selection: Set<Long> = if (selected.isEmpty()) emptySet() else items.mapNotNullTo(mutableSetOf()) { it.id.takeIf { id -> id in selected } }

    val selecting: Boolean = selection.isNotEmpty()

    /** Pin flips the whole selection, so it unpins only when every one of them is already pinned. */
    val selectionPinned: Boolean = selecting && items.none { it.id in selection && !it.pinned }

    /** The picker has somewhere to send them; a lone topic has nowhere. */
    val canMoveSelection: Boolean = selecting && moveTargets.isNotEmpty()
}

data class TypeFilter(val type: ItemType, val count: Int)

sealed interface TopicDetailIntent {
    data class SelectFilter(val type: ItemType?) : TopicDetailIntent
    data class SelectMode(val mode: TopicViewMode) : TopicDetailIntent
    data class OpenItem(val item: TopicItem) : TopicDetailIntent

    /** Tick an article read, a video watched or a bill paid — or untick it. */
    data class SetItemDone(val item: TopicItem, val done: Boolean) : TopicDetailIntent

    /** Have the agent (re)write the topic's summary: Summarise, Refresh and Try again. */
    data object Summarize : TopicDetailIntent

    // ── Selection (Figma: Topics 1e) ──
    /** Long-pressing an item picks it out and puts the feed into selection mode. */
    data class StartSelection(val itemId: Long) : TopicDetailIntent
    data class ToggleSelection(val itemId: Long) : TopicDetailIntent
    data object SelectAll : TopicDetailIntent
    data object ClearSelection : TopicDetailIntent

    /** Pins the selection, or unpins it when all of them already are. */
    data object PinSelection : TopicDetailIntent
    data object AskMoveSelection : TopicDetailIntent
    data object CancelMoveSelection : TopicDetailIntent
    data class MoveSelectionTo(val topicId: Long) : TopicDetailIntent
    data object AskDeleteSelection : TopicDetailIntent
    data object CancelDeleteSelection : TopicDetailIntent
    data object ConfirmDeleteSelection : TopicDetailIntent

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

    /** Hand a kept doc, image or invoice to whichever app opens that type. */
    data class OpenFile(val file: StoredFile) : TopicDetailEffect
    data class ShareText(val subject: String, val text: String) : TopicDetailEffect
    data class ShowMessage(val text: String) : TopicDetailEffect

    /** The topic is gone: deleted here, or elsewhere while open. */
    data object Close : TopicDetailEffect
}
