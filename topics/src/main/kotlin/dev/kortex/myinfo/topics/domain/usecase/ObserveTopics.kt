package dev.kortex.myinfo.topics.domain.usecase

import dev.kortex.myinfo.topics.domain.model.TopicOverview
import dev.kortex.myinfo.topics.domain.repository.TopicsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * The topics list (Figma: Topics 1a): one overview per topic, unordered. Order them with
 * [dev.kortex.myinfo.topics.domain.model.sortedFor].
 */
class ObserveTopics(private val repository: TopicsRepository) {
    operator fun invoke(): Flow<List<TopicOverview>> =
        combine(repository.observeTopics(), repository.observeItems()) { topics, items ->
            val itemsByTopic = items.groupBy { it.topicId }
            topics.map { TopicOverview.of(it, itemsByTopic[it.id].orEmpty()) }
        }
}
