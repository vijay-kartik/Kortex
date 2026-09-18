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

/** Keeps items at the top of their topic, whichever way its feed is arranged (Figma: Topics 1e). */
class SetItemsPinned(
    private val repository: TopicsRepository,
    private val clock: Clock,
) {
    suspend operator fun invoke(itemIds: Collection<Long>, pinned: Boolean) {
        if (itemIds.isEmpty()) return
        repository.setItemsPinned(itemIds, pinned, clock.nowMillis())
    }
}

/**
 * Ticks an item off: an article read, a video watched, a bill paid (Figma: Topics 1b). It counts
 * as working on the topic, so the topic moves up Recent.
 */
class SetItemDone(
    private val repository: TopicsRepository,
    private val clock: Clock,
) {
    suspend operator fun invoke(itemId: Long, done: Boolean) = repository.setItemDone(itemId, done, clock.nowMillis())
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
