package dev.kortex.myinfo.topics.domain.usecase

import dev.kortex.myinfo.topics.domain.repository.TopicsRepository

/** Pinned topics sit above the rest in Recent (Figma: Topics 1a). */
class SetTopicPinned(private val repository: TopicsRepository) {
    suspend operator fun invoke(topicId: Long, pinned: Boolean) = repository.setPinned(topicId, pinned)
}

/** Deletes the topic and its items; its links stay in the Links library. */
class DeleteTopic(private val repository: TopicsRepository) {
    suspend operator fun invoke(topicId: Long) = repository.deleteTopic(topicId)
}
