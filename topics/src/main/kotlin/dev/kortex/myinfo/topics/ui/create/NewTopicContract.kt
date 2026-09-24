package dev.kortex.myinfo.topics.ui.create

/**
 * New-topic form (Figma: Topics 1g). The name and purpose fields keep their text in the screen,
 * where typing can't lag behind a state round-trip; they reach the ViewModel with [NewTopicIntent.Submit].
 */
data class NewTopicState(
    val pinned: Boolean = false,
    /** Set from submit until the topic is saved, or the name is rejected. */
    val saving: Boolean = false,
    val nameError: NameError? = null,
)

enum class NameError {
    Blank,

    /** Another topic has this name, ignoring case. */
    Taken,
}

sealed interface NewTopicIntent {
    data class SetPinned(val pinned: Boolean) : NewTopicIntent

    /** The name changed, so an error about the old one no longer applies. */
    data object NameEdited : NewTopicIntent
    data class Submit(val name: String, val purpose: String) : NewTopicIntent
}

sealed interface NewTopicEffect {
    data class Created(val topicId: Long) : NewTopicEffect
}
