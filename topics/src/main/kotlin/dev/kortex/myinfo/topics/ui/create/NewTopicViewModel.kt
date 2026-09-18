package dev.kortex.myinfo.topics.ui.create

import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.kortex.mvi.MviViewModel
import dev.kortex.myinfo.topics.domain.model.TopicDraft
import dev.kortex.myinfo.topics.domain.model.TopicSaveResult
import dev.kortex.myinfo.topics.domain.usecase.CreateTopic
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class NewTopicViewModel @Inject constructor(
    private val createTopic: CreateTopic,
) : MviViewModel<NewTopicState, NewTopicIntent, NewTopicEffect>(NewTopicState()) {

    override fun handleIntent(intent: NewTopicIntent) {
        when (intent) {
            is NewTopicIntent.ToggleSection -> setState {
                copy(sections = if (intent.type in sections) sections - intent.type else sections + intent.type)
            }
            is NewTopicIntent.SetPinned -> setState { copy(pinned = intent.pinned) }
            NewTopicIntent.NameEdited -> if (currentState.nameError != null) setState { copy(nameError = null) }
            is NewTopicIntent.Submit -> submit(intent.name, intent.purpose)
        }
    }

    private fun submit(name: String, purpose: String) {
        if (currentState.saving) return
        setState { copy(saving = true, nameError = null) }
        val draft = currentState.let { TopicDraft(name = name, purpose = purpose, sections = it.sections, pinned = it.pinned) }
        viewModelScope.launch {
            when (val result = createTopic(draft)) {
                // Stays saving: the form is on its way out, and a second tap mustn't save twice.
                is TopicSaveResult.Saved -> sendEffect(NewTopicEffect.Created(result.topicId))
                TopicSaveResult.BlankName -> setState { copy(saving = false, nameError = NameError.Blank) }
                TopicSaveResult.NameTaken -> setState { copy(saving = false, nameError = NameError.Taken) }
            }
        }
    }
}
