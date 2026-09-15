package dev.kortex.app.ui.screens.links

sealed interface LinksScreenUiState {
    data object EmptyLinksUiState: LinksScreenUiState
    data object LinksUiState: LinksScreenUiState
}