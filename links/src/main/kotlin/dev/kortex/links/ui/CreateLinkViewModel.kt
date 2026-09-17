package dev.kortex.links.ui

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.kortex.links.data.LinksRepository
import dev.kortex.links.images.LinkImageSource
import dev.kortex.links.images.LinkImageState
import dev.kortex.links.images.LinkImageStore
import dev.kortex.links.tagging.PageMetadataFetcher
import dev.kortex.links.tagging.TagSuggester
import dev.kortex.links.tagging.tagCandidates
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** How far reading the typed address has got. */
enum class PageReadPhase {
    /** No usable address yet, or it hasn't settled. */
    Idle,
    ReadingPage,

    /** The page is in; tags are still being matched. The image loads alongside, on its own clock. */
    SuggestingTags,
    Done,
}

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
)

private data class LinkAnalysis(
    val url: String = "",
    val suggestedTitle: String? = null,
    val suggestedTags: List<String> = emptyList(),
    val candidateTags: List<String> = emptyList(),
    val phase: PageReadPhase = PageReadPhase.Idle,
    val imageUrl: String? = null,
    val image: PreviewImage = PreviewImage.Unknown,
)

@OptIn(FlowPreview::class)
@HiltViewModel
class CreateLinkViewModel @Inject constructor(
    private val repository: LinksRepository,
    private val metadataFetcher: PageMetadataFetcher,
    private val tagSuggester: TagSuggester,
    private val imageStore: LinkImageStore,
) : ViewModel() {
    private val url = MutableStateFlow("")
    private val analysis = MutableStateFlow(LinkAnalysis())

    val uiState: StateFlow<CreateLinkUiState> =
        combine(repository.observeTagNames(), analysis) { tags, current ->
            CreateLinkUiState(
                tags = tags,
                analyzedUrl = current.url,
                suggestedTitle = current.suggestedTitle,
                suggestedTags = current.suggestedTags,
                // Filtered here rather than in analyze() so a candidate disappears as soon as it's created.
                candidateTags = current.candidateTags
                    .filterNot { candidate -> tags.any { it.equals(candidate, ignoreCase = true) } }
                    .take(MAX_CANDIDATE_TAGS),
                phase = current.phase,
                image = current.image,
            )
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

    /** Tries a failed image download again; the preview follows along. */
    fun retryImage() {
        analysis.value.imageUrl?.let(imageStore::retry)
    }

    private var isSaving = false

    /**
     * Ignores repeat taps while a save is in flight. Saving doesn't wait for the page or its image:
     * whatever is still loading finishes in the background and lands on the saved link.
     */
    fun save(url: String, title: String, tags: List<String>, imageHidden: Boolean, onSaved: () -> Unit) {
        if (isSaving) return
        isSaving = true
        val current = analysis.value
        val image = when {
            current.url != url || current.phase == PageReadPhase.Idle || current.phase == PageReadPhase.ReadingPage -> LinkImageSource.Unknown
            current.imageUrl != null -> LinkImageSource.Known(current.imageUrl)
            else -> LinkImageSource.None
        }
        viewModelScope.launch {
            try {
                repository.saveLink(url, title, tags, image, imageHidden)
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
            analysis.value = LinkAnalysis(url = url)
            return
        }
        analysis.value = LinkAnalysis(url = url, phase = PageReadPhase.ReadingPage)

        val page = metadataFetcher.fetch(url)
        val imageUrl = page.imageUrl
        analysis.value = LinkAnalysis(
            url = url,
            suggestedTitle = page.title,
            candidateTags = tagCandidates(page),
            phase = PageReadPhase.SuggestingTags,
            imageUrl = imageUrl,
            image = if (imageUrl == null) PreviewImage.None else PreviewImage.Loading(fraction = null),
        )

        coroutineScope {
            // Keeps watching after the download ends so a retry shows up; a newer URL cancels it.
            if (imageUrl != null) {
                launch {
                    imageStore.image(imageUrl).collect { state -> analysis.update { it.copy(image = state.toPreview()) } }
                }
            }

            val suggestedTags = try {
                tagSuggester.suggest(page).map { it.tagName }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Suggestions are best-effort (e.g. model asset missing); the form still works without them.
                Log.w(TAG, "Tag suggestion failed for $url", e)
                emptyList()
            }
            analysis.update { it.copy(suggestedTags = suggestedTags, phase = PageReadPhase.Done) }
        }
    }

    private fun LinkImageState.toPreview(): PreviewImage = when (this) {
        is LinkImageState.Loading -> PreviewImage.Loading(fraction)
        is LinkImageState.Ready -> PreviewImage.Ready(path, width, height)
        LinkImageState.Failed -> PreviewImage.Failed
    }

    private companion object {
        const val TAG = "CreateLinkViewModel"
        const val URL_DEBOUNCE_MS = 600L
        const val MAX_CANDIDATE_TAGS = 3
    }
}
