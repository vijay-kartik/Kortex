package dev.kortex.myinfo.topics.ui.detail

import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.kortex.mvi.MviViewModel
import dev.kortex.myinfo.topics.domain.model.TopicItem
import dev.kortex.myinfo.topics.domain.model.storedFile
import dev.kortex.myinfo.topics.domain.usecase.DeleteTopic
import dev.kortex.myinfo.topics.domain.usecase.ObserveTopic
import dev.kortex.myinfo.topics.domain.usecase.SetItemDone
import dev.kortex.myinfo.topics.domain.usecase.SetTopicPinned
import kotlinx.coroutines.launch

@HiltViewModel(assistedFactory = TopicDetailViewModel.Factory::class)
class TopicDetailViewModel @AssistedInject constructor(
    @Assisted private val topicId: Long,
    observeTopic: ObserveTopic,
    private val setTopicPinned: SetTopicPinned,
    private val setItemDone: SetItemDone,
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
                if (detail == null) close() else setState { copy(detail = detail) }
            }
        }
    }

    override fun handleIntent(intent: TopicDetailIntent) {
        when (intent) {
            is TopicDetailIntent.SelectFilter -> setState { copy(filter = intent.type) }
            is TopicDetailIntent.OpenItem -> open(intent.item)
            is TopicDetailIntent.SetItemDone -> viewModelScope.launch { setItemDone(intent.item.id, intent.done) }
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
