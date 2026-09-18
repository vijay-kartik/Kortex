package dev.kortex.myinfo.topics.domain.usecase

import dev.kortex.myinfo.topics.domain.model.SearchCorpus
import dev.kortex.myinfo.topics.domain.repository.TopicsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * What search reads (Figma: Topics 1f). It re-emits as topics and items change, so a search left
 * open updates itself; the matching is [SearchCorpus.search]'s, and happens in memory.
 *
 * Deliberately not an FTS table: a topic's link-backed items keep only a reference, and their
 * titles live in the Links library — an index over `topic_items` alone would miss every link
 * title. This reads the same items the topics list already loads, so one matcher covers
 * everything and there is nothing to keep in step with the rows.
 */
class ObserveSearchCorpus(private val repository: TopicsRepository) {
    operator fun invoke(): Flow<SearchCorpus> =
        combine(repository.observeTopics(), repository.observeItems()) { topics, items ->
            SearchCorpus(topics, items.groupBy { it.topicId })
        }
}
