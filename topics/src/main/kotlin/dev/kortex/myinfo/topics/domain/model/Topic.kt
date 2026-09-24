package dev.kortex.myinfo.topics.domain.model

data class Topic(
    val id: Long,
    val name: String,
    /** "Why you're keeping it": optional, and given to the agent when it summarises the topic. */
    val purpose: String?,
    val pinned: Boolean,
    /**
     * Sections shown even while empty. Topics are no longer given any — their sections come from
     * what they hold — but ones made before keep the sections chosen for them then.
     */
    val sections: Set<ItemType>,
    val createdAtMillis: Long,
    /** Last time the topic or its items changed. Pinning doesn't count. */
    val updatedAtMillis: Long,
)

/** The editable part of a [Topic], as the new-topic form submits it. */
data class TopicDraft(
    val name: String,
    val purpose: String? = null,
    val sections: Set<ItemType> = emptySet(),
    val pinned: Boolean = false,
)

/** Outcome of creating or editing a topic. */
sealed interface TopicSaveResult {
    data class Saved(val topicId: Long) : TopicSaveResult
    data object BlankName : TopicSaveResult

    /** Another topic already has this name, ignoring case. */
    data object NameTaken : TopicSaveResult
}
