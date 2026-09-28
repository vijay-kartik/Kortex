package dev.kortex.links.ui.list

import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.kortex.links.domain.model.Link
import dev.kortex.links.domain.port.Clock
import dev.kortex.links.domain.usecase.DeleteLink
import dev.kortex.links.domain.usecase.ObserveLinks
import dev.kortex.links.domain.usecase.ObserveTagCounts
import dev.kortex.links.domain.usecase.SetLinkTags
import dev.kortex.mvi.MviViewModel
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class LinksViewModel @Inject constructor(
    observeLinks: ObserveLinks,
    observeTagCounts: ObserveTagCounts,
    private val setLinkTags: SetLinkTags,
    private val deleteLink: DeleteLink,
    private val clock: Clock,
) : MviViewModel<LinksState, LinksIntent, LinksEffect>(LinksState()) {

    /** Deleted, or being deleted, in the database; link ids are never reused. */
    private val committedDeletions = mutableSetOf<Long>()
    private var deletionJob: Job? = null

    init {
        // A deleted link can outlive its row by one emission; don't let it flash back as a card.
        observeLinks().reduceInto { links ->
            copy(loading = false, links = links.filterNot { it.id in committedDeletions }).normalized()
        }
        observeTagCounts().reduceInto { copy(tags = it).normalized() }
    }

    override fun handleIntent(intent: LinksIntent) {
        when (intent) {
            is LinksIntent.QueryChanged -> update { copy(query = intent.query) }
            is LinksIntent.ToggleTagFilter -> update {
                copy(selectedTags = if (intent.tag in selectedTags) selectedTags - intent.tag else selectedTags + intent.tag)
            }
            is LinksIntent.Copy -> link(intent.linkId)?.let { sendEffect(LinksEffect.CopyUrl(it.url)) }
            is LinksIntent.ShowOptions ->
                // Long-pressing the open card again mustn't drop its draft.
                if (intent.linkId != currentState.openOptionsLinkId) update { copy(optionsLinkId = intent.linkId, tagDraft = null) }
            is LinksIntent.CloseOptions -> closeOptions(intent.typedTagName)
            is LinksIntent.Open -> link(intent.linkId)?.let {
                closeTray()
                sendEffect(LinksEffect.OpenUrl(it.url))
            }
            is LinksIntent.Share -> link(intent.linkId)?.let {
                closeTray()
                sendEffect(LinksEffect.Share(it.url, it.title))
            }
            is LinksIntent.Delete -> startDeletion(intent.linkId)
            LinksIntent.UndoDelete -> {
                deletionJob?.cancel()
                update { copy(pendingDeletion = null) }
            }
            LinksIntent.EditTags -> currentState.openLink?.let { link -> update { copy(tagDraft = TagDraft(link.tags)) } }
            is LinksIntent.ToggleDraftTag -> update { copy(tagDraft = tagDraft?.toggled(intent.tag)) }
            LinksIntent.StartNewTag -> update { copy(tagDraft = tagDraft?.copy(adding = true)) }
            is LinksIntent.AddDraftTag -> update { copy(tagDraft = draftTagsWith(intent.name)?.let { TagDraft(it) }) }
            LinksIntent.CreateLink -> sendEffect(LinksEffect.OpenCreateLink)
        }
    }

    /** Saves the draft if it changed, then closes the tray. */
    private fun closeOptions(typedTagName: String) {
        val linkId = currentState.openOptionsLinkId
        val tags = currentState.tagsToSave(typedTagName)
        if (linkId != null && tags != null) viewModelScope.launch { setLinkTags(linkId, tags) }
        closeTray()
    }

    private fun closeTray() = update { copy(optionsLinkId = null, tagDraft = null) }

    /**
     * Hides the link behind an undo row and deletes it once [UNDO_WINDOW_MS] passes. Only one link
     * is restorable at a time: deleting another ends the previous window early.
     */
    private fun startDeletion(linkId: Long) {
        commitPendingDeletion()
        val now = clock.nowMillis()
        update {
            copy(optionsLinkId = null, tagDraft = null, pendingDeletion = PendingLinkDeletion(linkId, startedAtMillis = now, deadlineMillis = now + UNDO_WINDOW_MS))
        }
        deletionJob = viewModelScope.launch {
            delay(UNDO_WINDOW_MS)
            commitPendingDeletion()
        }
    }

    private fun commitPendingDeletion() {
        val pending = currentState.pendingDeletion ?: return
        deletionJob?.cancel()
        committedDeletions += pending.linkId
        update { copy(pendingDeletion = null, links = links.filterNot { it.id == pending.linkId }) }
        viewModelScope.launch { deleteLink(pending.linkId) }
    }

    @OptIn(DelicateCoroutinesApi::class)
    override fun onCleared() {
        // Leaving ends the undo window early rather than dropping the delete. viewModelScope is
        // already cancelled here, and the delete is a short, self-contained database write.
        val pending = currentState.pendingDeletion ?: return
        GlobalScope.launch { deleteLink(pending.linkId) }
    }

    private fun link(id: Long): Link? = currentState.links.firstOrNull { it.id == id }

    /** Every change goes through here, so a tray whose link left the screen closes for good. */
    private fun update(reducer: LinksState.() -> LinksState) = setState { reducer().normalized() }

    private companion object {
        const val UNDO_WINDOW_MS = 5_000L
    }
}
