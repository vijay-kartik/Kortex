package dev.kortex.myinfo.topics.domain.model

/**
 * A topic the user could start from links they already saved (Figma: Topics 1g, first run).
 * Suggestions come from Links tags, which are themselves assigned by embedding similarity.
 */
data class TopicSuggestion(
    val name: String,
    val links: List<SavedLink>,
    /** [ItemType.Video] when most of [links] are videos, else [ItemType.Link]. */
    val kind: ItemType,
)
