package dev.kortex.app.ui.screens.links

import dev.kortex.links.data.LinkWithTags
import dev.kortex.links.data.TagLinkCount

sealed interface LinksScreenUiState {
    /** Before the first database read, so the empty state doesn't flash for users who have links. */
    data object LoadingUiState : LinksScreenUiState
    data object EmptyLinksUiState : LinksScreenUiState
    data class LinksUiState(
        /** Saved links after the search and tag filters are applied, newest first. */
        val links: List<LinkWithTags>,
        val tags: List<TagLinkCount>,
        val selectedTags: Set<String>,
    ) : LinksScreenUiState
}
