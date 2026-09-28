package dev.kortex.links.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.kortex.links.domain.model.Link
import dev.kortex.links.domain.port.Clock
import dev.kortex.links.domain.usecase.DeleteLink
import dev.kortex.links.domain.usecase.ObserveLinks
import dev.kortex.links.domain.usecase.ObserveTagCounts
import dev.kortex.links.domain.usecase.SetLinkTags
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class LinksViewModel @Inject constructor(
    observeLinks: ObserveLinks,
    observeTagCounts: ObserveTagCounts,
    private val setLinkTags: SetLinkTags,
    private val deleteLink: DeleteLink,
    private val clock: Clock,
) : ViewModel() {
    private val query = MutableStateFlow("")
    private val selectedTags = MutableStateFlow(emptySet<String>())
    private val deletions = MutableStateFlow(Deletions())
    private var deletionJob: Job? = null

    val linksScreenUiState: StateFlow<LinksScreenUiState> =
        combine(observeLinks(), observeTagCounts(), query, selectedTags, deletions) { links, tags, query, selected, deletions ->
            // A deleted link can outlive its row by one emission; don't let it flash back as a card.
            val saved = links.filterNot { it.id in deletions.committedIds }
            if (saved.isEmpty()) {
                LinksScreenUiState.EmptyLinksUiState
            } else {
                val pending = deletions.pending?.let { pending -> saved.firstOrNull { it.id == pending.linkId }?.let { pending to it } }
                val pendingTags = pending?.second?.tags.orEmpty()
                val counts = tags.map { if (it.name in pendingTags) it.copy(linkCount = it.linkCount - 1) else it }
                // Drop selections for tags that no longer exist so they can't hide every link.
                val activeTags = selected.filterTo(mutableSetOf()) { name -> tags.any { it.name == name } }
                LinksScreenUiState.LinksUiState(
                    links = saved.filter { it.matches(query.trim()) && it.tags.containsAll(activeTags) },
                    tags = counts,
                    selectedTags = activeTags,
                    linkCount = saved.size - if (pending != null) 1 else 0,
                    pendingDeletion = pending?.first,
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

    fun saveTags(id: Long, tagNames: List<String>) {
        viewModelScope.launch { setLinkTags(id, tagNames) }
    }

    /**
     * Hides the link behind an undo row and deletes it once [UNDO_WINDOW_MS] passes. Only one link
     * is restorable at a time: deleting another ends the previous window early.
     */
    fun startDeletion(id: Long) {
        commitPendingDeletion()
        val now = clock.nowMillis()
        deletions.update { it.copy(pending = PendingDeletion(id, startedAtMillis = now, deadlineMillis = now + UNDO_WINDOW_MS)) }
        deletionJob = viewModelScope.launch {
            delay(UNDO_WINDOW_MS)
            commitPendingDeletion()
        }
    }

    fun undoDelete() {
        deletionJob?.cancel()
        deletions.update { it.copy(pending = null) }
    }

    private fun commitPendingDeletion() {
        val pending = deletions.value.pending ?: return
        deletionJob?.cancel()
        deletions.update { Deletions(committedIds = it.committedIds + pending.linkId) }
        viewModelScope.launch { deleteLink(pending.linkId) }
    }

    @OptIn(DelicateCoroutinesApi::class)
    override fun onCleared() {
        // Leaving ends the undo window early rather than dropping the delete. viewModelScope is
        // already cancelled here, and the delete is a short, self-contained database write.
        val pending = deletions.value.pending ?: return
        GlobalScope.launch { deleteLink(pending.linkId) }
    }

    private fun Link.matches(query: String): Boolean =
        query.isEmpty() ||
                title.contains(query, ignoreCase = true) ||
                url.contains(query, ignoreCase = true) ||
                tags.any { it.contains(query, ignoreCase = true) }

    /** [committedIds] are deleted, or being deleted, in the database; link ids are never reused. */
    private data class Deletions(val pending: PendingDeletion? = null, val committedIds: Set<Long> = emptySet())

    private companion object {
        const val UNDO_WINDOW_MS = 5_000L
    }
}
