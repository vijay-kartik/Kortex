package dev.kortex.myinfo.topics.ui.detail

import dev.kortex.myinfo.topics.domain.model.FeedSection
import dev.kortex.myinfo.topics.domain.model.ItemType
import dev.kortex.myinfo.topics.domain.model.SavedEmail
import dev.kortex.myinfo.topics.domain.model.StoredFile
import dev.kortex.myinfo.topics.domain.model.TopicDetail
import dev.kortex.myinfo.topics.domain.model.TopicFeed
import dev.kortex.myinfo.topics.domain.model.TopicItem
import dev.kortex.myinfo.topics.domain.model.TopicSummary
import dev.kortex.myinfo.topics.domain.model.TopicViewMode
import dev.kortex.myinfo.topics.domain.model.done
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
    /** The email open in the reader over the feed. */
    val reading: SavedEmail? = null,
    /** The video playing in the player over the feed, by item id (Figma: Topic videos 2e). */
    val playing: Long? = null,
    /** The status chip is on: only what's still to watch, read or pay (Figma: Topic videos 2c). */
    val onlyUndone: Boolean = false,
    /** SELECT was tapped: selection mode with nothing picked yet (Figma: Topic videos 2d). */
    val selectionMode: Boolean = false,
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

    /**
     * A type is picked: the screen becomes that type's view (Figma: Topic videos 2b) — its own
     * header and totals, a status chip, and none of the topic-wide parts until ALL is picked again.
     */
    val typeView: ItemType? = activeFilter

    /** Everything of the picked type, done or not; the whole topic under ALL. */
    val typeItems: List<TopicItem> = detail?.items.orEmpty().filter { activeFilter == null || it.type == activeFilter }

    /** UNWATCHED 3, for a type that has a done state; null for the rest and under ALL. */
    val statusFilter: StatusFilter? = typeView?.let(::doneStatus)?.let { status ->
        StatusFilter(status, count = typeItems.count { it.done == false })
    }

    val showsOnlyUndone: Boolean = onlyUndone && statusFilter != null

    val items: List<TopicItem> = if (showsOnlyUndone) typeItems.filter { it.done == false } else typeItems

    /** Done items the status chip is keeping off screen: "2 watched videos hidden · SHOW". */
    val hiddenDoneCount: Int = typeItems.size - items.size

    // A type's view is one plain list, pinned first; the Feed / Timeline / By type cut is the topic's.
    val sections: List<FeedSection> = TopicFeed.sections(items, if (typeView != null) TopicViewMode.Feed else mode, nowMillis)

    /** Only items still on screen: one hidden by a filter, or deleted elsewhere, drops out. */
    val selection: Set<Long> = if (selected.isEmpty()) emptySet() else items.mapNotNullTo(mutableSetOf()) { it.id.takeIf { id -> id in selected } }

    val selecting: Boolean = selectionMode || selection.isNotEmpty()

    /** Pin flips the whole selection, so it unpins only when every one of them is already pinned. */
    val selectionPinned: Boolean = selection.isNotEmpty() && items.none { it.id in selection && !it.pinned }

    /** The picker has somewhere to send them; a lone topic has nowhere. */
    val canMoveSelection: Boolean = selection.isNotEmpty() && moveTargets.isNotEmpty()

    /**
     * Every picked item is of one type that can be done with, so the bar offers Mark watched, read
     * or paid (Figma: Topic videos 2d). Null for a mixed selection or for notes, links and the like.
     */
    val selectionDoneStatus: DoneStatus? =
        if (selection.isEmpty()) null else items.filter { it.id in selection }.map { it.type }.distinct().singleOrNull()?.let(::doneStatus)

    /** Mark flips the whole selection, like Pin: it unmarks only when every one of them is already done. */
    val selectionDone: Boolean = selectionDoneStatus != null && items.none { it.id in selection && it.done == false }
}

data class TypeFilter(val type: ItemType, val count: Int)

/** How a type says it's done with: watched, read, paid. */
data class DoneStatus(
    /** The chip: "UNWATCHED". */
    val undone: String,
    /** "watched", as in "2 watched videos hidden". */
    val done: String,
)

data class StatusFilter(val status: DoneStatus, val count: Int)

/** Null for the types that are never done with. */
fun doneStatus(type: ItemType): DoneStatus? = when (type) {
    ItemType.Video -> DoneStatus(undone = "UNWATCHED", done = "watched")
    ItemType.Article -> DoneStatus(undone = "UNREAD", done = "read")
    ItemType.Bill -> DoneStatus(undone = "UNPAID", done = "paid")
    ItemType.Note, ItemType.Link, ItemType.Doc, ItemType.Image, ItemType.Email -> null
}

sealed interface TopicDetailIntent {
    data class SelectFilter(val type: ItemType?) : TopicDetailIntent

    /** The status chip: show only what's still to do, or everything again. */
    data object ToggleOnlyUndone : TopicDetailIntent
    data class SelectMode(val mode: TopicViewMode) : TopicDetailIntent
    data class OpenItem(val item: TopicItem) : TopicDetailIntent

    /** Back out of the email reader to the feed. */
    data object CloseEmail : TopicDetailIntent

    /** Back out of the video player to the feed. */
    data object ClosePlayer : TopicDetailIntent

    /** Tick an article read, a video watched or a bill paid — or untick it. */
    data class SetItemDone(val item: TopicItem, val done: Boolean) : TopicDetailIntent

    /** Have the agent (re)write the topic's summary: Summarise, Refresh and Try again. */
    data object Summarize : TopicDetailIntent

    // ── Selection (Figma: Topics 1e) ──
    /** Long-pressing an item picks it out and puts the feed into selection mode. */
    data class StartSelection(val itemId: Long) : TopicDetailIntent

    /** SELECT on the hint bar: selection mode with nothing picked yet. */
    data object EnterSelectionMode : TopicDetailIntent
    data class ToggleSelection(val itemId: Long) : TopicDetailIntent
    data object SelectAll : TopicDetailIntent
    data object ClearSelection : TopicDetailIntent

    /** Pins the selection, or unpins it when all of them already are. */
    data object PinSelection : TopicDetailIntent

    /** Marks the selection watched (read, paid), or back when all of them already are. */
    data object MarkSelectionDone : TopicDetailIntent
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

    /** Put [text] on the clipboard: tapping a note copies it. */
    data class CopyText(val text: String) : TopicDetailEffect
    data class ShowMessage(val text: String) : TopicDetailEffect

    /** The topic is gone: deleted here, or elsewhere while open. */
    data object Close : TopicDetailEffect
}
