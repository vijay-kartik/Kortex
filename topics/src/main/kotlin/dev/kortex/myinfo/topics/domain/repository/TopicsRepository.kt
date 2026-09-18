package dev.kortex.myinfo.topics.domain.repository

import dev.kortex.myinfo.topics.domain.model.NewItem
import dev.kortex.myinfo.topics.domain.model.Topic
import dev.kortex.myinfo.topics.domain.model.TopicDraft
import dev.kortex.myinfo.topics.domain.model.TopicItem
import kotlinx.coroutines.flow.Flow

/**
 * Topics and their items. Link-backed items resolve through the Links library; one whose link has
 * been deleted there is left out of every result, as if it were gone.
 */
interface TopicsRepository {
    fun observeTopics(): Flow<List<Topic>>

    fun observeTopic(id: Long): Flow<Topic?>

    /** Every item of every topic, newest first. */
    fun observeItems(): Flow<List<TopicItem>>

    /** The topic's items, newest first. */
    fun observeItems(topicId: Long): Flow<List<TopicItem>>

    /** @return the new topic's id, or null when another topic has this name, ignoring case. */
    suspend fun createTopic(draft: TopicDraft, nowMillis: Long): Long?

    /** @return false, changing nothing, when another topic has this name, ignoring case. */
    suspend fun updateTopic(id: Long, draft: TopicDraft, nowMillis: Long): Boolean

    /** Doesn't count as a change to the topic: its updated time stays. */
    suspend fun setPinned(id: Long, pinned: Boolean)

    /** Removes the topic and its items. Links it held stay in the Links library. */
    suspend fun deleteTopic(id: Long)

    /** @return the new item's id, or null when it is a link the topic already holds. */
    suspend fun addItem(topicId: Long, item: NewItem, nowMillis: Long): Long?

    /** An item whose link the target topic already holds is dropped rather than duplicated. */
    suspend fun moveItems(itemIds: Collection<Long>, toTopicId: Long, nowMillis: Long)

    suspend fun deleteItems(itemIds: Collection<Long>, nowMillis: Long)
}
