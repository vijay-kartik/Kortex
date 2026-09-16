package dev.kortex.app.ui.screens.links

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.kortex.links.data.LinksRepository
import dev.kortex.links.tagging.PageMetadataFetcher
import dev.kortex.links.tagging.TagSuggester
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class CreateLinkUiState(
    val tags: List<String> = emptyList(),
    /** Title the page gives itself; the screen fills it in only while the title field is empty. */
    val suggestedTitle: String? = null,
    val suggestedTags: List<String> = emptyList(),
    val isAnalyzing: Boolean = false,
)

private data class LinkAnalysis(
    val suggestedTitle: String? = null,
    val suggestedTags: List<String> = emptyList(),
    val isAnalyzing: Boolean = false,
)

@OptIn(FlowPreview::class)
@HiltViewModel
class CreateLinkViewModel @Inject constructor(
    private val repository: LinksRepository,
    private val metadataFetcher: PageMetadataFetcher,
    private val tagSuggester: TagSuggester,
) : ViewModel() {
    private val url = MutableStateFlow("")
    private val analysis = MutableStateFlow(LinkAnalysis())

    val uiState: StateFlow<CreateLinkUiState> =
        combine(repository.observeTagNames(), analysis) { tags, current ->
            CreateLinkUiState(tags, current.suggestedTitle, current.suggestedTags, current.isAnalyzing)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CreateLinkUiState())

    init {
        viewModelScope.launch {
            url.debounce(URL_DEBOUNCE_MS)
                .map { it.trim() }
                .distinctUntilChanged()
                // A newer URL cancels the fetch/embedding for the previous one.
                .collectLatest { analyze(it) }
        }
    }

    fun onUrlChange(value: String) {
        url.value = value
    }

    fun createTag(name: String) {
        if (name.isBlank()) return
        viewModelScope.launch { repository.createTag(name) }
    }

    private var isSaving = false

    /** Ignores repeat taps while a save is in flight. */
    fun save(url: String, title: String, tags: List<String>, onSaved: () -> Unit) {
        if (isSaving) return
        isSaving = true
        viewModelScope.launch {
            try {
                repository.saveLink(url, title, tags)
                onSaved()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Saving link failed for $url", e)
            } finally {
                isSaving = false
            }
        }
    }

    private suspend fun analyze(url: String) {
        if (linkDomain(url) == null) {
            analysis.value = LinkAnalysis()
            return
        }
        analysis.value = LinkAnalysis(isAnalyzing = true)

        val page = metadataFetcher.fetch(url)
        analysis.value = LinkAnalysis(suggestedTitle = page.title, isAnalyzing = true)

        val suggestedTags = try {
            tagSuggester.suggest(page).map { it.tagName }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Suggestions are best-effort (e.g. model asset missing); the form still works without them.
            Log.w(TAG, "Tag suggestion failed for $url", e)
            emptyList()
        }
        analysis.value = LinkAnalysis(suggestedTitle = page.title, suggestedTags = suggestedTags)
    }

    private companion object {
        const val TAG = "CreateLinkViewModel"
        const val URL_DEBOUNCE_MS = 600L
    }
}
