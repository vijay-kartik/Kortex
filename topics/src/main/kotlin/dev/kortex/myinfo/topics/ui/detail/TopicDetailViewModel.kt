package dev.kortex.myinfo.topics.ui.detail

import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.kortex.mvi.MviViewModel
import dev.kortex.myinfo.topics.domain.model.TopicItem
import dev.kortex.myinfo.topics.domain.model.TopicSort
import dev.kortex.myinfo.topics.domain.model.sortedFor
import dev.kortex.myinfo.topics.domain.model.storedFile
import dev.kortex.myinfo.topics.domain.port.Clock
import dev.kortex.myinfo.topics.domain.usecase.DeleteItems
import dev.kortex.myinfo.topics.domain.usecase.DeleteTopic
import dev.kortex.myinfo.topics.domain.usecase.MoveItems
import dev.kortex.myinfo.topics.domain.usecase.ObserveTopic
import dev.kortex.myinfo.topics.domain.usecase.ObserveTopics
import dev.kortex.myinfo.topics.domain.usecase.SetItemDone
import dev.kortex.myinfo.topics.domain.usecase.SetItemsPinned
import dev.kortex.myinfo.topics.domain.usecase.SetTopicPinned
import dev.kortex.myinfo.topics.ui.common.TopicChoice
import kotlinx.coroutines.launch

@HiltViewModel(assistedFactory = TopicDetailViewModel.Factory::class)
class TopicDetailViewModel @AssistedInject constructor(
    @Assisted private val topicId: Long,
    observeTopic: ObserveTopic,
    observeTopics: ObserveTopics,
    private val clock: Clock,
    private val setTopicPinned: SetTopicPinned,
    private val setItemDone: SetItemDone,
    private val setItemsPinned: SetItemsPinned,
    private val moveItems: MoveItems,
    private val deleteItems: DeleteItems,
    private val deleteTopic: DeleteTopic,
) : MviViewModel<TopicDetailState, TopicDetailIntent, TopicDetailEffect>(TopicDetailState()) {

    @AssistedFactory
    interface Factory {
        fun create(topicId: Long): TopicDetailViewModel
    }

    private var closing = false

    init {
        viewModelScope.launch {
            observeTopic(topicId).collect { detail ->
                // Re-stamped with the feed, so date groups and ages never go stale under it.
                if (detail == null) close() else setState { copy(detail = detail, nowMillis = clock.nowMillis()) }
            }
        }
        // Everywhere the selection could move to; this topic isn't one of them.
        observeTopics().reduceInto { overviews ->
            copy(
                moveTargets = overviews.sortedFor(TopicSort.Recent)
                    .filterNot { it.topic.id == topicId }
                    .map { TopicChoice(it.topic.id, it.topic.name) },
            )
        }
    }

    override fun handleIntent(intent: TopicDetailIntent) {
        when (intent) {
            // Filtering hides items, and a selection the user can't see isn't one they can judge.
            is TopicDetailIntent.SelectFilter -> setState { copy(filter = intent.type, selected = emptySet()) }
            is TopicDetailIntent.SelectMode -> setState { copy(mode = intent.mode, nowMillis = clock.nowMillis()) }
            is TopicDetailIntent.OpenItem -> open(intent.item)
            is TopicDetailIntent.SetItemDone -> viewModelScope.launch { setItemDone(intent.item.id, intent.done) }

            is TopicDetailIntent.StartSelection -> setState { copy(selected = selected + intent.itemId) }
            is TopicDetailIntent.ToggleSelection -> setState {
                copy(selected = if (intent.itemId in selected) selected - intent.itemId else selected + intent.itemId)
            }
            TopicDetailIntent.SelectAll -> setState { copy(selected = items.mapTo(mutableSetOf()) { it.id }) }
            TopicDetailIntent.ClearSelection -> clearSelection()
            TopicDetailIntent.PinSelection -> withSelection { ids, state ->
                val pinned = !state.selectionPinned
                setItemsPinned(ids, pinned)
                report(ids.size, if (pinned) "Pinned" else "Unpinned")
            }
            TopicDetailIntent.AskMoveSelection -> setState { copy(movingSelection = true) }
            TopicDetailIntent.CancelMoveSelection -> setState { copy(movingSelection = false) }
            is TopicDetailIntent.MoveSelectionTo -> {
                setState { copy(movingSelection = false) }
                withSelection { ids, state ->
                    moveItems(ids, intent.topicId)
                    val name = state.moveTargets.firstOrNull { it.id == intent.topicId }?.name
                    sendEffect(TopicDetailEffect.ShowMessage(movedMessage(ids.size, name)))
                }
            }
            TopicDetailIntent.AskDeleteSelection -> setState { copy(confirmingSelectionDelete = true) }
            TopicDetailIntent.CancelDeleteSelection -> setState { copy(confirmingSelectionDelete = false) }
            TopicDetailIntent.ConfirmDeleteSelection -> {
                setState { copy(confirmingSelectionDelete = false) }
                withSelection { ids, _ ->
                    deleteItems(ids)
                    report(ids.size, "Deleted")
                }
            }

            TopicDetailIntent.Add -> setState { copy(capturing = true) }
            TopicDetailIntent.CloseCapture -> setState { copy(capturing = false) }
            is TopicDetailIntent.Captured -> {
                setState { copy(capturing = false) }
                // Saved here, the new card says so; saved elsewhere, nothing on screen would.
                if (intent.topicId != topicId) sendEffect(TopicDetailEffect.ShowMessage("Saved to ${intent.topicName}"))
            }
            TopicDetailIntent.Share -> currentState.detail?.let { detail ->
                sendEffect(TopicDetailEffect.ShareText(subject = detail.topic.name, text = topicShareText(detail)))
            }
            is TopicDetailIntent.SetPinned -> viewModelScope.launch { setTopicPinned(topicId, intent.pinned) }
            TopicDetailIntent.AskDelete -> setState { copy(confirmingDelete = true) }
            TopicDetailIntent.CancelDelete -> setState { copy(confirmingDelete = false) }
            TopicDetailIntent.ConfirmDelete -> {
                setState { copy(confirmingDelete = false) }
                viewModelScope.launch {
                    deleteTopic(topicId)
                    close()
                }
            }
        }
    }

    /**
     * Runs [action] on the items picked out right now, then leaves selection mode. The ids are
     * read once up front, so the feed re-emitting mid-action can't change what was acted on.
     */
    private fun withSelection(action: suspend (ids: Set<Long>, state: TopicDetailState) -> Unit) {
        val state = currentState
        val ids = state.selection
        if (ids.isEmpty()) return
        clearSelection()
        viewModelScope.launch { action(ids, state) }
    }

    private fun clearSelection() = setState { copy(selected = emptySet(), movingSelection = false, confirmingSelectionDelete = false) }

    /** "3 items pinned", "1 item deleted". */
    private fun report(count: Int, verb: String) =
        sendEffect(TopicDetailEffect.ShowMessage("$count ${itemNoun(count)} ${verb.lowercase()}"))

    private fun movedMessage(count: Int, topicName: String?): String =
        if (topicName == null) "$count ${itemNoun(count)} moved" else "$count ${itemNoun(count)} moved to $topicName"

    private fun itemNoun(count: Int) = if (count == 1) "item" else "items"

    /** Once, whether the delete finishes first or the topic's row disappears first. */
    private fun close() {
        if (closing) return
        closing = true
        sendEffect(TopicDetailEffect.Close)
    }

    /** Where tapping an item goes: a link to the browser, a kept file to whatever opens its type. */
    private fun open(item: TopicItem) {
        when (item) {
            is TopicItem.Link -> sendEffect(TopicDetailEffect.OpenUrl(item.link.url))
            is TopicItem.Article -> sendEffect(TopicDetailEffect.OpenUrl(item.link.url))
            is TopicItem.Video -> sendEffect(TopicDetailEffect.OpenUrl(item.link.url))
            // A bill opens its invoice when it has one; without one there is nothing to show yet.
            is TopicItem.Doc, is TopicItem.Image, is TopicItem.Bill ->
                item.storedFile?.let { sendEffect(TopicDetailEffect.OpenFile(it)) }
            is TopicItem.Note -> Unit
        }
    }
}
