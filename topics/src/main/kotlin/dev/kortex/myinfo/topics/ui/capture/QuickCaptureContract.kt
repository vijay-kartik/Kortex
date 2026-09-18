package dev.kortex.myinfo.topics.ui.capture

import dev.kortex.myinfo.topics.domain.model.ItemType
import dev.kortex.myinfo.topics.domain.model.LinkLookup
import dev.kortex.myinfo.topics.domain.usecase.Detection
import dev.kortex.myinfo.topics.domain.usecase.captureTypes
import dev.kortex.myinfo.topics.domain.usecase.defaultCaptureType

/**
 * Quick-capture sheet (Figma: Topics 1d). The pasted text, title and new-topic name live in the
 * sheet's fields; the ViewModel hears about text changes to detect the type and look the address
 * up, and gets all three with [QuickCaptureIntent.Save].
 */
data class QuickCaptureState(
    val detection: Detection = Detection(ItemType.Note, url = null),
    val blank: Boolean = true,
    /** The user's pick; ignored while it doesn't suit the text. */
    val chosenType: ItemType? = null,
    /** For [Detection.url]; null until the lookup finishes. */
    val lookup: LinkLookup? = null,
    val lookingUp: Boolean = false,
    val topics: List<TopicChoice> = emptyList(),
    val selectedTopicId: Long? = null,
    /** "+ New topic" is open: the item goes into a topic named in its field. */
    val creatingTopic: Boolean = false,
    val saving: Boolean = false,
    val error: CaptureError? = null,
) {
    val types: List<ItemType> = captureTypes(detection)
    val type: ItemType = chosenType?.takeIf { it in types } ?: defaultCaptureType(detection)

    /** The type on screen is the one detected, not a change the user made. */
    val typeIsDetected: Boolean = !blank && type == defaultCaptureType(detection)

    val canSave: Boolean = !blank && !saving && (creatingTopic || selectedTopicId != null)
}

data class TopicChoice(val id: Long, val name: String)

enum class CaptureError {
    AlreadyInTopic,
    NewTopicNameBlank,
    NewTopicNameTaken,
}

sealed interface QuickCaptureIntent {
    data class TextChanged(val text: String) : QuickCaptureIntent
    data class ChooseType(val type: ItemType) : QuickCaptureIntent
    data class SelectTopic(val topicId: Long) : QuickCaptureIntent
    data object StartNewTopic : QuickCaptureIntent

    /** The new topic's name changed, so an error about the old one no longer applies. */
    data object NewTopicNameEdited : QuickCaptureIntent
    data class Save(val text: String, val title: String, val newTopicName: String) : QuickCaptureIntent
}

sealed interface QuickCaptureEffect {
    data class Saved(val topicId: Long, val topicName: String) : QuickCaptureEffect
}
