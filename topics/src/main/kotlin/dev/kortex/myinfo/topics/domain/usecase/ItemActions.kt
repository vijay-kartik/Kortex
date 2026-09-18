package dev.kortex.myinfo.topics.domain.usecase

import dev.kortex.myinfo.topics.domain.port.Clock
import dev.kortex.myinfo.topics.domain.repository.TopicsRepository

/** Moves items to another topic (Figma: Topics 1e); a link the target already holds isn't duplicated. */
class MoveItems(
    private val repository: TopicsRepository,
    private val clock: Clock,
) {
    suspend operator fun invoke(itemIds: Collection<Long>, toTopicId: Long) {
        if (itemIds.isEmpty()) return
        repository.moveItems(itemIds, toTopicId, clock.nowMillis())
    }
}

/** Removes items from their topics. Their links, if any, stay in the Links library. */
class DeleteItems(
    private val repository: TopicsRepository,
    private val clock: Clock,
) {
    suspend operator fun invoke(itemIds: Collection<Long>) {
        if (itemIds.isEmpty()) return
        repository.deleteItems(itemIds, clock.nowMillis())
    }
}
