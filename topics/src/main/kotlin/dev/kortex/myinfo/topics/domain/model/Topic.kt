package dev.kortex.myinfo.topics.domain.model

data class Topic(
    val id: Long,
    val name: String,
    /** "Why you're keeping it": optional, and given to the agent when it summarises the topic. */
    val purpose: String?,
    val pinned: Boolean,
    /** Sections the user chose to show. Types outside this set still show once the topic holds one. */
    val sections: Set<ItemType>,
    val createdAtMillis: Long,
    /** Last time the topic or its items changed. Pinning doesn't count. */
    val updatedAtMillis: Long,
)

/** The editable part of a [Topic], as the new-topic form submits it. */
data class TopicDraft(
    val name: String,
    val purpose: String? = null,
    val sections: Set<ItemType> = ItemType.DefaultSections,
    val pinned: Boolean = false,
)

/** Outcome of creating or editing a topic. */
sealed interface TopicSaveResult {
    data class Saved(val topicId: Long) : TopicSaveResult
    data object BlankName : TopicSaveResult

    /** Another topic already has this name, ignoring case. */
    data object NameTaken : TopicSaveResult
}
