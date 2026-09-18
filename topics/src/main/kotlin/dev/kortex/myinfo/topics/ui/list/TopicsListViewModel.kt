package dev.kortex.myinfo.topics.ui.list

import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.kortex.mvi.MviViewModel
import dev.kortex.myinfo.topics.domain.model.TopicSaveResult
import dev.kortex.myinfo.topics.domain.model.TopicSuggestion
import dev.kortex.myinfo.topics.domain.port.Clock
import dev.kortex.myinfo.topics.domain.usecase.AcceptTopicSuggestion
import dev.kortex.myinfo.topics.domain.usecase.DeleteTopic
import dev.kortex.myinfo.topics.domain.usecase.ObserveTopicSuggestions
import dev.kortex.myinfo.topics.domain.usecase.ObserveTopics
import dev.kortex.myinfo.topics.domain.usecase.SetTopicPinned
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class TopicsListViewModel @Inject constructor(
    observeTopics: ObserveTopics,
    observeTopicSuggestions: ObserveTopicSuggestions,
    private val setTopicPinned: SetTopicPinned,
    private val deleteTopic: DeleteTopic,
    private val acceptTopicSuggestion: AcceptTopicSuggestion,
    private val clock: Clock,
) : MviViewModel<TopicsListState, TopicsListIntent, TopicsListEffect>(TopicsListState()) {

    /** Deleted, or being deleted, in the database; topic ids are never reused. */
    private val committedDeletions = mutableSetOf<Long>()
    private var deletionJob: Job? = null
    private var acceptJob: Job? = null

    init {
        // A deleted topic can outlive its row by one emission; don't let it flash back as a card.
        observeTopics().reduceInto { topics ->
            copy(loading = false, topics = topics.filterNot { it.topic.id in committedDeletions })
        }
        observeTopicSuggestions().reduceInto { copy(suggestions = it) }
    }

    override fun handleIntent(intent: TopicsListIntent) {
        when (intent) {
            is TopicsListIntent.SelectSort -> setState { copy(sort = intent.sort, optionsTopicId = null) }
            is TopicsListIntent.OpenTopic -> {
                setState { copy(optionsTopicId = null) }
                sendEffect(TopicsListEffect.OpenTopic(intent.topicId))
            }
            is TopicsListIntent.ShowOptions -> setState { copy(optionsTopicId = intent.topicId) }
            TopicsListIntent.HideOptions -> setState { copy(optionsTopicId = null) }
            is TopicsListIntent.SetPinned -> {
                setState { copy(optionsTopicId = null) }
                viewModelScope.launch { setTopicPinned(intent.topicId, intent.pinned) }
            }
            is TopicsListIntent.Delete -> startDeletion(intent.topicId)
            TopicsListIntent.UndoDelete -> {
                deletionJob?.cancel()
                setState { copy(pendingDeletion = null) }
            }
            TopicsListIntent.CreateTopic -> sendEffect(TopicsListEffect.OpenNewTopic)
            TopicsListIntent.Search -> sendEffect(TopicsListEffect.OpenSearch)
            is TopicsListIntent.AcceptSuggestion -> accept(intent.suggestion)
        }
    }

    /** Opens the new topic; a second tap while the first is saving does nothing. */
    private fun accept(suggestion: TopicSuggestion) {
        if (acceptJob?.isActive == true) return
        acceptJob = viewModelScope.launch {
            val result = acceptTopicSuggestion(suggestion)
            if (result is TopicSaveResult.Saved) sendEffect(TopicsListEffect.OpenTopic(result.topicId))
        }
    }

    /**
     * Hides the topic behind an undo row and deletes it once [UNDO_WINDOW_MS] passes. Only one
     * topic is restorable at a time: deleting another ends the previous window early.
     */
    private fun startDeletion(topicId: Long) {
        commitPendingDeletion()
        val now = clock.nowMillis()
        setState {
            copy(optionsTopicId = null, pendingDeletion = PendingTopicDeletion(topicId, startedAtMillis = now, deadlineMillis = now + UNDO_WINDOW_MS))
        }
        deletionJob = viewModelScope.launch {
            delay(UNDO_WINDOW_MS)
            commitPendingDeletion()
        }
    }

    private fun commitPendingDeletion() {
        val pending = currentState.pendingDeletion ?: return
        deletionJob?.cancel()
        committedDeletions += pending.topicId
        setState { copy(pendingDeletion = null, topics = topics.filterNot { it.topic.id == pending.topicId }) }
        viewModelScope.launch { deleteTopic(pending.topicId) }
    }

    @OptIn(DelicateCoroutinesApi::class)
    override fun onCleared() {
        // Leaving ends the undo window early rather than dropping the delete. viewModelScope is
        // already cancelled here, and the delete is a short, self-contained database write.
        val pending = currentState.pendingDeletion ?: return
        GlobalScope.launch { deleteTopic(pending.topicId) }
    }

    private companion object {
        const val UNDO_WINDOW_MS = 5_000L
    }
}
