package dev.kortex.links.ui.create

import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.kortex.links.domain.model.LinkDraft
import dev.kortex.links.domain.repository.SaveLinkResult
import dev.kortex.links.domain.usecase.AnalyzeLink
import dev.kortex.links.domain.usecase.CreateTag
import dev.kortex.links.domain.usecase.ObserveDuplicate
import dev.kortex.links.domain.usecase.ObserveTagNames
import dev.kortex.links.domain.usecase.RetryLinkImage
import dev.kortex.links.domain.usecase.SaveLink
import dev.kortex.mvi.MviViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds

/**
 * One per opening of the form: [CreateLinkRoute] scopes it, so nothing carries over from a
 * previous form. The address field stays in the screen, which reports every edit.
 */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
@HiltViewModel
class CreateLinkViewModel @Inject constructor(
    observeTagNames: ObserveTagNames,
    observeDuplicate: ObserveDuplicate,
    analyzeLink: AnalyzeLink,
    private val createTag: CreateTag,
    private val retryLinkImage: RetryLinkImage,
    private val saveLink: SaveLink,
) : MviViewModel<CreateLinkState, CreateLinkIntent, CreateLinkEffect>(CreateLinkState()) {

    /** The address field's text, as last reported. */
    private val typedUrl = MutableStateFlow("")

    init {
        observeTagNames().reduceInto { copy(tags = it) }
        // Not debounced like the analysis: Save is blocked by this, so it keeps up with typing.
        typedUrl.map { it.trim() }
            .distinctUntilChanged()
            .flatMapLatest { observeDuplicate(it) }
            .reduceInto { copy(alreadySaved = it) }
        // Typing and undoing within the debounce changes nothing, and a read already running goes on;
        // a newer address cancels the older read, and no stale result can land after it.
        typedUrl.debounce(URL_DEBOUNCE_MS.milliseconds)
            .map { it.trim() }
            .distinctUntilChanged()
            .flatMapLatest { analyzeLink(it) }
            .reduceInto { withAnalysis(it) }
    }

    override fun handleIntent(intent: CreateLinkIntent) {
        when (intent) {
            is CreateLinkIntent.UrlChanged -> typedUrl.value = intent.url
            is CreateLinkIntent.ToggleTag -> setState { toggledTag(intent.tag) }
            CreateLinkIntent.StartNewTag -> setState { copy(addingTag = true) }
            CreateLinkIntent.CancelNewTag -> setState { copy(addingTag = false) }
            is CreateLinkIntent.AddTag -> addTag(intent.name)
            is CreateLinkIntent.SetImageHidden -> {
                // Showing an image that never arrived means trying it again.
                if (!intent.hidden && currentState.imageFor(typedUrl.value) == PreviewImage.Failed) retryImage()
                setState { copy(imageHidden = intent.hidden) }
            }
            CreateLinkIntent.RetryImage -> retryImage()
            is CreateLinkIntent.Save -> save(intent.url, intent.title)
        }
    }

    /**
     * Selects the tag, in its stored spelling when it exists ignoring case. A new one is created
     * right away, before the link is saved, so its candidate chip disappears.
     */
    private fun addTag(name: String) {
        val tag = currentState.tagNamed(name) ?: return setState { copy(addingTag = false) }
        if (currentState.isNewTag(tag)) viewModelScope.launch { createTag(tag) }
        setState { withTagAdded(tag) }
    }

    private fun retryImage() {
        currentState.analysis.imageUrl?.let { retryLinkImage(it) }
    }

    /**
     * Saves right away, without waiting for the page or its image: whatever is still loading lands
     * on the saved link. A second tap while saving does nothing.
     */
    private fun save(url: String, title: String) {
        val state = currentState
        if (!state.canSave(url)) return
        setState { copy(saving = true) }
        val address = url.trim()
        viewModelScope.launch {
            val draft = LinkDraft(address, title.trim(), state.selectedTags, state.analysis.imageSourceFor(address), state.imageHidden)
            when (saveLink(draft)) {
                // Stays saving: the form is on its way out, and a second tap mustn't save twice.
                is SaveLinkResult.Saved -> {
                    setState { copy(saved = true) }
                    sendEffect(CreateLinkEffect.Saved)
                }
                // The live check lagged the field; alreadySaved now shows why.
                SaveLinkResult.AlreadySaved -> setState { copy(saving = false) }
                SaveLinkResult.Failed -> setState { copy(saving = false) }
            }
        }
    }

    private companion object {
        const val URL_DEBOUNCE_MS = 600L
    }
}
