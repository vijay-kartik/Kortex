package dev.kortex.myinfo.topics.data

import android.database.sqlite.SQLiteConstraintException
import dev.kortex.myinfo.topics.data.local.TopicDao
import dev.kortex.myinfo.topics.data.local.TopicItemEntity
import dev.kortex.myinfo.topics.data.local.TopicSummaryEntity
import dev.kortex.myinfo.topics.data.local.toColumn
import dev.kortex.myinfo.topics.data.local.toDomain
import dev.kortex.myinfo.topics.data.local.toEntity
import dev.kortex.myinfo.topics.domain.model.NewItem
import dev.kortex.myinfo.topics.domain.model.Topic
import dev.kortex.myinfo.topics.domain.model.TopicDraft
import dev.kortex.myinfo.topics.domain.model.TopicItem
import dev.kortex.myinfo.topics.domain.model.TopicSummary
import dev.kortex.myinfo.topics.domain.port.FileVault
import dev.kortex.myinfo.topics.domain.port.LinkCatalog
import dev.kortex.myinfo.topics.domain.repository.TopicsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/**
 * [TopicsRepository] on Room, with link-backed items resolved through [linkCatalog]. A link
 * deleted from the Links library leaves its topic rows behind; they are filtered out on read, and
 * since link ids are never reused they can't come back pointing at a different link.
 *
 * Files are the other way round: they belong to the topic, so deleting an item or a topic takes
 * its files out of [fileVault] too. The rows go first — a file left behind is swept later, while
 * a row pointing at a deleted file would show as a broken item.
 */
class RoomTopicsRepository(
    private val dao: TopicDao,
    private val linkCatalog: LinkCatalog,
    private val fileVault: FileVault,
) : TopicsRepository {
    override fun observeTopics(): Flow<List<Topic>> = dao.observeTopics().map { rows -> rows.map { it.toDomain() } }

    override fun observeTopic(id: Long): Flow<Topic?> = dao.observeTopic(id).map { it?.toDomain() }

    override fun observeItems(): Flow<List<TopicItem>> = dao.observeItems().resolved()

    override fun observeItems(topicId: Long): Flow<List<TopicItem>> = dao.observeItems(topicId).resolved()

    override suspend fun createTopic(draft: TopicDraft, nowMillis: Long): Long? =
        try {
            dao.insertTopic(draft.toEntity(nowMillis))
        } catch (e: SQLiteConstraintException) {
            null // the unique name index
        }

    override suspend fun updateTopic(id: Long, draft: TopicDraft, nowMillis: Long): Boolean =
        try {
            dao.updateTopic(id, draft.name, draft.purpose, draft.sections.toColumn(), draft.pinned, nowMillis)
            true
        } catch (e: SQLiteConstraintException) {
            false // the unique name index
        }

    override suspend fun setPinned(id: Long, pinned: Boolean) = dao.setPinned(id, pinned)

    override suspend fun deleteTopic(id: Long) {
        val files = dao.filePathsOfTopic(id)
        dao.deleteTopic(id)
        fileVault.delete(files)
    }

    override suspend fun addItem(topicId: Long, item: NewItem, nowMillis: Long): Long? {
        // Saved to Links first: a link already there is reused, not duplicated.
        val linkId = (item as? NewItem.Link)?.let { linkCatalog.findOrSave(it.url, it.title) }
        return dao.addItem(item.toEntity(topicId, linkId, nowMillis), nowMillis).takeIf { it != -1L }
    }

    override suspend fun setItemDone(itemId: Long, done: Boolean, nowMillis: Long) = dao.setDone(itemId, done, nowMillis)

    override suspend fun setItemsPinned(itemIds: Collection<Long>, pinned: Boolean, nowMillis: Long) =
        dao.setItemsPinned(itemIds, pinned, nowMillis)

    override suspend fun moveItems(itemIds: Collection<Long>, toTopicId: Long, nowMillis: Long) =
        dao.moveItems(itemIds, toTopicId, nowMillis)

    override suspend fun deleteItems(itemIds: Collection<Long>, nowMillis: Long) {
        val files = dao.filePathsOf(itemIds)
        dao.deleteItems(itemIds, nowMillis)
        fileVault.delete(files)
    }

    override fun observeSummary(topicId: Long): Flow<TopicSummary?> =
        dao.observeSummary(topicId).map { row ->
            row?.let { TopicSummary(it.topicId, it.text, it.generatedAtMillis, it.fingerprint) }
        }

    override suspend fun saveSummary(summary: TopicSummary) {
        try {
            dao.upsertSummary(
                TopicSummaryEntity(summary.topicId, summary.text, summary.generatedAtMillis, summary.fingerprint),
            )
        } catch (e: SQLiteConstraintException) {
            // The topic was deleted while its summary was being written; nothing to keep it for.
        }
    }

    private fun Flow<List<TopicItemEntity>>.resolved(): Flow<List<TopicItem>> =
        combine(this, linkCatalog.observeLinks()) { rows, links -> rows.mapNotNull { it.toDomain(links) } }
}
