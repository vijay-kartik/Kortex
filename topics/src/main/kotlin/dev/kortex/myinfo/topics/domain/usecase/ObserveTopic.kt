package dev.kortex.myinfo.topics.domain.usecase

import dev.kortex.myinfo.topics.domain.model.TopicDetail
import dev.kortex.myinfo.topics.domain.repository.TopicsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/** One topic with its items (Figma: Topics 1b); null once the topic is deleted. */
class ObserveTopic(private val repository: TopicsRepository) {
    operator fun invoke(topicId: Long): Flow<TopicDetail?> =
        combine(repository.observeTopic(topicId), repository.observeItems(topicId)) { topic, items ->
            topic?.let { TopicDetail(it, items) }
        }
}
