package dev.kortex.myinfo.topics.ui.search

import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.kortex.mvi.MviViewModel
import dev.kortex.myinfo.topics.domain.model.SearchCorpus
import dev.kortex.myinfo.topics.domain.model.SearchResults
import dev.kortex.myinfo.topics.domain.model.SearchScope
import dev.kortex.myinfo.topics.domain.usecase.ObserveSearchCorpus
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class TopicSearchViewModel @Inject constructor(
    observeSearchCorpus: ObserveSearchCorpus,
) : MviViewModel<TopicSearchState, TopicSearchIntent, TopicSearchEffect>(TopicSearchState()) {

    /** Held outside the state: it is what search reads, not anything the screen draws. */
    private var corpus = SearchCorpus.Empty

    private var debounce: Job? = null

    init {
        viewModelScope.launch {
            observeSearchCorpus().collect { loaded ->
                corpus = loaded
                // Something changed under an open search; show it rather than stale results.
                if (!currentState.idle) rerun(currentState.query, currentState.scope)
            }
        }
    }

    override fun handleIntent(intent: TopicSearchIntent) {
        when (intent) {
            is TopicSearchIntent.QueryChanged -> onQueryChanged(intent.query)
            // A scope change is a tap, not typing: answer it at once.
            is TopicSearchIntent.SelectScope -> {
                debounce?.cancel()
                rerun(currentState.query, intent.scope)
            }
            is TopicSearchIntent.OpenTopic -> sendEffect(TopicSearchEffect.OpenTopic(intent.topicId))
        }
    }

    private fun onQueryChanged(query: String) {
        debounce?.cancel()
        if (query.isBlank()) {
            // Clearing the field goes back to the opening screen immediately.
            setState { copy(query = "", results = SearchResults.none(scope), searching = false, idle = true) }
            return
        }
        setState { copy(searching = true, idle = false) }
        debounce = viewModelScope.launch {
            delay(DEBOUNCE_MS)
            rerun(query, currentState.scope)
        }
    }

    private fun rerun(query: String, scope: SearchScope) {
        val results = corpus.search(query, scope)
        setState {
            copy(
                query = query,
                scope = scope,
                results = results,
                searching = false,
                idle = query.isBlank(),
            )
        }
    }

    private companion object {
        /** Long enough that a word typed at speed searches once, short enough to feel live. */
        const val DEBOUNCE_MS = 220L
    }
}
