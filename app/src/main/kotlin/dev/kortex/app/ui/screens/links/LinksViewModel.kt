package dev.kortex.app.ui.screens.links

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.kortex.links.data.LinkWithTags
import dev.kortex.links.data.LinksRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class LinksViewModel @Inject constructor(
    repository: LinksRepository,
) : ViewModel() {
    private val query = MutableStateFlow("")
    private val selectedTags = MutableStateFlow(emptySet<String>())

    val linksScreenUiState: StateFlow<LinksScreenUiState> =
        combine(repository.observeLinks(), repository.observeTagLinkCounts(), query, selectedTags) { links, tags, query, selected ->
            if (links.isEmpty()) {
                LinksScreenUiState.EmptyLinksUiState
            } else {
                // Drop selections for tags that no longer exist so they can't hide every link.
                val activeTags = selected.filterTo(mutableSetOf()) { name -> tags.any { it.name == name } }
                LinksScreenUiState.LinksUiState(
                    links = links.filter { it.matches(query.trim()) && it.tagNames.containsAll(activeTags) },
                    tags = tags,
                    selectedTags = activeTags,
                )
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LinksScreenUiState.LoadingUiState)

    fun onQueryChange(value: String) {
        query.value = value
    }

    /** Selected tags narrow the list: a link must carry all of them. */
    fun toggleTag(name: String) {
        selectedTags.value = selectedTags.value.let { if (name in it) it - name else it + name }
    }

    private fun LinkWithTags.matches(query: String): Boolean =
        query.isEmpty() ||
            link.title.contains(query, ignoreCase = true) ||
            link.url.contains(query, ignoreCase = true) ||
            tagNames.any { it.contains(query, ignoreCase = true) }
}
