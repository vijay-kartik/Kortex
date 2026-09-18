package dev.kortex.myinfo.topics.ui.capture

import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.kortex.mvi.MviViewModel
import dev.kortex.myinfo.topics.domain.model.CaptureResult
import dev.kortex.myinfo.topics.domain.model.CaptureTarget
import dev.kortex.myinfo.topics.domain.model.TopicSort
import dev.kortex.myinfo.topics.domain.model.sortedFor
import dev.kortex.myinfo.topics.domain.usecase.CaptureItem
import dev.kortex.myinfo.topics.domain.usecase.DetectItemType
import dev.kortex.myinfo.topics.domain.usecase.LookUpLink
import dev.kortex.myinfo.topics.domain.usecase.ObserveTopics
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@HiltViewModel(assistedFactory = QuickCaptureViewModel.Factory::class)
class QuickCaptureViewModel @AssistedInject constructor(
    @Assisted initialTopicId: Long,
    observeTopics: ObserveTopics,
    private val detectItemType: DetectItemType,
    private val lookUpLink: LookUpLink,
    private val captureItem: CaptureItem,
) : MviViewModel<QuickCaptureState, QuickCaptureIntent, QuickCaptureEffect>(QuickCaptureState(selectedTopicId = initialTopicId)) {

    @AssistedFactory
    interface Factory {
        fun create(initialTopicId: Long): QuickCaptureViewModel
    }

    private var lookupJob: Job? = null

    init {
        // Same order as the topics list, so the topic the user works in most is near the front.
        observeTopics().reduceInto { overviews ->
            copy(
                topics = overviews.sortedFor(TopicSort.Recent).map { TopicChoice(it.topic.id, it.topic.name) },
                selectedTopicId = selectedTopicId?.takeIf { id -> overviews.any { it.topic.id == id } },
            )
        }
    }

    override fun handleIntent(intent: QuickCaptureIntent) {
        when (intent) {
            is QuickCaptureIntent.TextChanged -> onTextChanged(intent.text)
            is QuickCaptureIntent.ChooseType -> setState { copy(chosenType = intent.type) }
            // Every error is about the old target: the item already in it, or the new topic's name.
            is QuickCaptureIntent.SelectTopic -> setState { copy(selectedTopicId = intent.topicId, creatingTopic = false, error = null) }
            QuickCaptureIntent.StartNewTopic -> setState { copy(creatingTopic = true, error = null) }
            QuickCaptureIntent.NewTopicNameEdited -> setState {
                copy(error = error?.takeUnless { it == CaptureError.NewTopicNameBlank || it == CaptureError.NewTopicNameTaken })
            }
            is QuickCaptureIntent.Save -> save(intent)
        }
    }

    private fun onTextChanged(text: String) {
        val detection = detectItemType(text)
        val addressChanged = detection.url != currentState.detection.url
        setState {
            copy(
                detection = detection,
                blank = text.isBlank(),
                error = error?.takeUnless { it == CaptureError.AlreadyInTopic },
                lookup = if (addressChanged) null else lookup,
                lookingUp = if (addressChanged) detection.url != null else lookingUp,
            )
        }
        if (!addressChanged) return
        lookupJob?.cancel()
        val url = detection.url ?: return
        lookupJob = viewModelScope.launch {
            // Typing an address shouldn't read a page per keystroke.
            delay(LOOKUP_DEBOUNCE_MS)
            val lookup = lookUpLink(url)
            setState { if (detection.url == url) copy(lookup = lookup, lookingUp = false) else this }
        }
    }

    private fun save(intent: QuickCaptureIntent.Save) {
        val state = currentState
        if (!state.canSave) return
        val newName = intent.newTopicName.trim()
        val target = if (state.creatingTopic) CaptureTarget.New(newName) else CaptureTarget.Existing(checkNotNull(state.selectedTopicId))
        setState { copy(saving = true, error = null) }
        viewModelScope.launch {
            val result = captureItem(intent.text, state.type, intent.title.trim().ifEmpty { null }, target)
            when (result) {
                // Stays saving: the sheet is on its way out, and a second tap mustn't save twice.
                is CaptureResult.Saved -> {
                    val name = if (state.creatingTopic) newName else state.topics.firstOrNull { it.id == result.topicId }?.name.orEmpty()
                    sendEffect(QuickCaptureEffect.Saved(result.topicId, name))
                }
                CaptureResult.Invalid -> setState { copy(saving = false) }
                CaptureResult.AlreadyInTopic -> setState { copy(saving = false, error = CaptureError.AlreadyInTopic) }
                CaptureResult.NewTopicNameBlank -> setState { copy(saving = false, error = CaptureError.NewTopicNameBlank) }
                CaptureResult.NewTopicNameTaken -> setState { copy(saving = false, error = CaptureError.NewTopicNameTaken) }
            }
        }
    }

    private companion object {
        const val LOOKUP_DEBOUNCE_MS = 400L
    }
}
