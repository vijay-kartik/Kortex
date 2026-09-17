package dev.kortex.links.ui

import dev.kortex.links.data.LinkWithTags
import dev.kortex.links.data.TagLinkCount

sealed interface LinksScreenUiState {
    /** Before the first database read, so the empty state doesn't flash for users who have links. */
    data object LoadingUiState : LinksScreenUiState
    data object EmptyLinksUiState : LinksScreenUiState
    data class LinksUiState(
        /**
         * Saved links after the search and tag filters are applied, newest first. A link in its
         * undo window is still here, so it can show as an undo row in its place.
         */
        val links: List<LinkWithTags>,
        /** Link counts leave out the link in its undo window. */
        val tags: List<TagLinkCount>,
        val selectedTags: Set<String>,
        /** Saved links, not counting the one in its undo window. */
        val linkCount: Int = links.size,
        val pendingDeletion: PendingDeletion? = null,
    ) : LinksScreenUiState
}

/** A deleted link that stays restorable until [deadlineMillis] (wall clock). */
data class PendingDeletion(
    val linkId: Long,
    val startedAtMillis: Long,
    val deadlineMillis: Long,
)
