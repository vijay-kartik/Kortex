package dev.kortex.myinfo.topics.domain.model

/** An address as the Links library knows it, before it is saved into a topic. */
data class LinkLookup(
    /** The saved link's title if the address is in Links, else the page's own; null if neither is known. */
    val title: String?,
    /** Already in Links, so the topic will point at that link rather than save it again. */
    val inLinks: Boolean,
)

/** Which topic a captured item goes into. */
sealed interface CaptureTarget {
    data class Existing(val topicId: Long) : CaptureTarget

    /** A topic created on the spot, named [name]. */
    data class New(val name: String) : CaptureTarget
}

sealed interface CaptureResult {
    data class Saved(val topicId: Long) : CaptureResult

    /** Nothing valid to save: blank text, or a link type for text that isn't an address. */
    data object Invalid : CaptureResult
    data object AlreadyInTopic : CaptureResult
    data object NewTopicNameBlank : CaptureResult
    data object NewTopicNameTaken : CaptureResult
}
