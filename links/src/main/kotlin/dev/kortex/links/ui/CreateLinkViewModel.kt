package dev.kortex.links.ui

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.kortex.links.domain.model.AlreadySavedLink
import dev.kortex.links.domain.model.LinkAnalysis
import dev.kortex.links.domain.model.LinkDraft
import dev.kortex.links.domain.model.LinkImageState
import dev.kortex.links.domain.model.PageReadPhase
import dev.kortex.links.domain.repository.SaveLinkResult
import dev.kortex.links.domain.usecase.AnalyzeLink
import dev.kortex.links.domain.usecase.CreateTag
import dev.kortex.links.domain.usecase.ObserveDuplicate
import dev.kortex.links.domain.usecase.ObserveTagNames
import dev.kortex.links.domain.usecase.RetryLinkImage
import dev.kortex.links.domain.usecase.SaveLink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The page's share image as the preview card sees it. */
sealed interface PreviewImage {
    /** The page hasn't been read, so it isn't known whether there is one. */
    data object Unknown : PreviewImage

    /** The page names no image (or couldn't be read). */
    data object None : PreviewImage

    data class Loading(val fraction: Float?) : PreviewImage

    data class Ready(val path: String, val width: Int, val height: Int) : PreviewImage

    data object Failed : PreviewImage
}

data class CreateLinkUiState(
    val tags: List<String> = emptyList(),
    /** Address the suggestions below were read from; the view model outlives a single form. */
    val analyzedUrl: String = "",
    /** Title the page gives itself; the screen fills it in only while the title field is empty. */
    val suggestedTitle: String? = null,
    val suggestedTags: List<String> = emptyList(),
    /** New tag names read off the page, minus tags the user already has. */
    val candidateTags: List<String> = emptyList(),
    val phase: PageReadPhase = PageReadPhase.Idle,
    val image: PreviewImage = PreviewImage.Unknown,
    /** Set when the address in the field is already saved; saving it again is blocked. */
    val alreadySaved: AlreadySavedLink? = null,
)

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
@HiltViewModel
class CreateLinkViewModel @Inject constructor(
    observeTagNames: ObserveTagNames,
    observeDuplicate: ObserveDuplicate,
    analyzeLink: AnalyzeLink,
    private val createTag: CreateTag,
    private val retryLinkImage: RetryLinkImage,
    private val saveLink: SaveLink,
) : ViewModel() {
    private val url = MutableStateFlow("")
    private val analysis = MutableStateFlow(LinkAnalysis())

    // Not debounced like analysis: Save is disabled from this, so it should keep up with typing.
    private val alreadySaved: Flow<AlreadySavedLink?> = url
        .map { it.trim() }
        .distinctUntilChanged()
        .flatMapLatest { observeDuplicate(it) }

    val uiState: StateFlow<CreateLinkUiState> =
        combine(observeTagNames(), analysis, alreadySaved) { tags, current, saved ->
            CreateLinkUiState(
                tags = tags,
                analyzedUrl = current.url,
                suggestedTitle = current.suggestedTitle,
                suggestedTags = current.suggestedTags,
                // Filtered here rather than in the analysis so a candidate disappears as soon as it's created.
                candidateTags = current.candidateTags
                    .filterNot { candidate -> tags.any { it.equals(candidate, ignoreCase = true) } }
                    .take(MAX_CANDIDATE_TAGS),
                phase = current.phase,
                image = current.previewImage(),
                alreadySaved = saved,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CreateLinkUiState())

    init {
        viewModelScope.launch {
            url.debounce(URL_DEBOUNCE_MS)
                .map { it.trim() }
                .distinctUntilChanged()
                // A newer URL cancels the fetch/embedding for the previous one.
                .flatMapLatest { analyzeLink(it) }
                .collect { analysis.value = it }
        }
    }

    fun onUrlChange(value: String) {
        url.value = value
    }

    fun addTag(name: String) {
        viewModelScope.launch { createTag(name) }
    }

    /** Tries a failed image download again; the preview follows along. */
    fun retryImage() {
        analysis.value.imageUrl?.let { retryLinkImage(it) }
    }

    private var isSaving = false

    /**
     * Ignores repeat taps while a save is in flight. Saving doesn't wait for the page or its image:
     * whatever is still loading finishes in the background and lands on the saved link.
     * [onSaved] isn't called when the address turns out to be saved already; [CreateLinkUiState.alreadySaved] shows why.
     */
    fun save(url: String, title: String, tags: List<String>, imageHidden: Boolean, onSaved: () -> Unit) {
        if (isSaving) return
        isSaving = true
        val image = analysis.value.imageSourceFor(url)
        viewModelScope.launch {
            try {
                if (saveLink(LinkDraft(url, title, tags, image, imageHidden)) is SaveLinkResult.Saved) onSaved()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Saving link failed for $url", e)
            } finally {
                isSaving = false
            }
        }
    }

    private fun LinkAnalysis.previewImage(): PreviewImage = when {
        phase == PageReadPhase.Idle || phase == PageReadPhase.ReadingPage -> PreviewImage.Unknown
        imageUrl == null -> PreviewImage.None
        else -> when (val download = image) {
            null, is LinkImageState.Loading -> PreviewImage.Loading((download as? LinkImageState.Loading)?.fraction)
            is LinkImageState.Ready -> PreviewImage.Ready(download.path, download.width, download.height)
            LinkImageState.Failed -> PreviewImage.Failed
        }
    }

    private companion object {
        const val TAG = "CreateLinkViewModel"
        const val URL_DEBOUNCE_MS = 600L
        const val MAX_CANDIDATE_TAGS = 3
    }
}
