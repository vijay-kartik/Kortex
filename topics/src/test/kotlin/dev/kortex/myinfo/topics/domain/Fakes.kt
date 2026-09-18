package dev.kortex.myinfo.topics.domain

import dev.kortex.myinfo.topics.domain.model.LinkLookup
import dev.kortex.myinfo.topics.domain.model.NewItem
import dev.kortex.myinfo.topics.domain.model.SavedLink
import dev.kortex.myinfo.topics.domain.model.Topic
import dev.kortex.myinfo.topics.domain.model.TopicDraft
import dev.kortex.myinfo.topics.domain.model.TopicItem
import dev.kortex.myinfo.topics.domain.port.LinkCatalog
import dev.kortex.myinfo.topics.domain.repository.TopicsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/**
 * Records writes; names are unique ignoring case and a topic holds an address once, as in Room.
 * Reads come from [observedTopics] and [observedItems], which tests set directly.
 */
class FakeTopicsRepository : TopicsRepository {
    val topics = mutableListOf<TopicDraft>()
    val items = mutableListOf<NewItem>()
    var moves = 0
    val pinned = mutableMapOf<Long, Boolean>()
    val deleted = mutableListOf<Long>()

    val observedTopics = MutableStateFlow<List<Topic>>(emptyList())
    val observedItems = MutableStateFlow<List<TopicItem>>(emptyList())

    override fun observeTopics(): Flow<List<Topic>> = observedTopics
    override fun observeTopic(id: Long): Flow<Topic?> = observedTopics.map { topics -> topics.firstOrNull { it.id == id } }
    override fun observeItems(): Flow<List<TopicItem>> = observedItems
    override fun observeItems(topicId: Long): Flow<List<TopicItem>> = observedItems.map { items -> items.filter { it.topicId == topicId } }

    override suspend fun createTopic(draft: TopicDraft, nowMillis: Long): Long? {
        if (topics.any { it.name.equals(draft.name, ignoreCase = true) }) return null
        topics += draft
        return topics.size.toLong()
    }

    override suspend fun updateTopic(id: Long, draft: TopicDraft, nowMillis: Long): Boolean {
        val index = (id - 1).toInt()
        if (topics.withIndex().any { (i, it) -> i != index && it.name.equals(draft.name, ignoreCase = true) }) return false
        topics[index] = draft
        return true
    }

    override suspend fun setPinned(id: Long, pinned: Boolean) {
        this.pinned[id] = pinned
    }

    override suspend fun deleteTopic(id: Long) {
        deleted += id
        observedTopics.update { topics -> topics.filterNot { it.id == id } }
    }

    override suspend fun addItem(topicId: Long, item: NewItem, nowMillis: Long): Long? {
        if (item is NewItem.Link && items.any { it is NewItem.Link && it.url == item.url }) return null
        items += item
        return items.size.toLong()
    }

    override suspend fun moveItems(itemIds: Collection<Long>, toTopicId: Long, nowMillis: Long) {
        moves++
    }

    override suspend fun deleteItems(itemIds: Collection<Long>, nowMillis: Long) = Unit
}

/** Nothing saved; [findOrSave] hands out fresh ids and [lookUp] answers from [lookups]. */
class FakeLinkCatalog : LinkCatalog {
    private var nextId = 1L
    val lookups = mutableMapOf<String, LinkLookup>()

    override fun observeLinks(): Flow<Map<Long, SavedLink>> = emptyFlow()

    override suspend fun findOrSave(url: String, title: String?): Long = nextId++

    override suspend fun lookUp(url: String): LinkLookup = lookups[url] ?: LinkLookup(title = null, inLinks = false)
}
