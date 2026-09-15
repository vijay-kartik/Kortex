package dev.kortex.app.ui.screens.links

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

@HiltViewModel
class LinksViewModel @Inject constructor(): ViewModel() {
    private val _linksScreenUiState = MutableStateFlow<LinksScreenUiState>(LinksScreenUiState.EmptyLinksUiState)
    val linksScreenUiState: StateFlow<LinksScreenUiState> = _linksScreenUiState.asStateFlow()
}