package dev.kortex.myinfo.topics.domain.usecase

import dev.kortex.myinfo.topics.domain.model.RecentFirst
import dev.kortex.myinfo.topics.domain.model.Topic
import dev.kortex.myinfo.topics.domain.repository.TopicsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Every topic, in the topics list's recent order, for pickers that only need names. Reads the
 * topics alone, so writes to items or links (a video's progress, say) don't re-emit it.
 */
class ObserveTopicChoices(private val repository: TopicsRepository) {
    operator fun invoke(): Flow<List<Topic>> =
        repository.observeTopics().map { topics -> topics.sortedWith(RecentFirst) }
}
