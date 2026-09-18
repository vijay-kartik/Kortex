package dev.kortex.myinfo.topics.domain.usecase

import dev.kortex.myinfo.topics.domain.model.TopicDraft
import dev.kortex.myinfo.topics.domain.model.TopicSaveResult
import dev.kortex.myinfo.topics.domain.port.Clock
import dev.kortex.myinfo.topics.domain.repository.TopicsRepository

/** Creates a topic (Figma: Topics 1g). Names are trimmed and must be unique, ignoring case. */
class CreateTopic(
    private val repository: TopicsRepository,
    private val clock: Clock,
) {
    suspend operator fun invoke(draft: TopicDraft): TopicSaveResult {
        val clean = draft.cleaned() ?: return TopicSaveResult.BlankName
        val id = repository.createTopic(clean, clock.nowMillis()) ?: return TopicSaveResult.NameTaken
        return TopicSaveResult.Saved(id)
    }
}

/** Edits a topic, by the same rules as [CreateTopic]. */
class UpdateTopic(
    private val repository: TopicsRepository,
    private val clock: Clock,
) {
    suspend operator fun invoke(topicId: Long, draft: TopicDraft): TopicSaveResult {
        val clean = draft.cleaned() ?: return TopicSaveResult.BlankName
        if (!repository.updateTopic(topicId, clean, clock.nowMillis())) return TopicSaveResult.NameTaken
        return TopicSaveResult.Saved(topicId)
    }
}

/** Trimmed, with a blank purpose dropped; null when the name is blank. */
private fun TopicDraft.cleaned(): TopicDraft? {
    val name = name.trim().ifEmpty { return null }
    return copy(name = name, purpose = purpose?.trim()?.ifEmpty { null })
}
