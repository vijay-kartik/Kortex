package dev.kortex.myinfo.topics.data.local

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** A topic with unpushed changes, with the summary its document carries. */
data class DirtyTopic(
    @Embedded val topic: TopicEntity,
    @Relation(parentColumn = "id", entityColumn = "topicId")
    val summary: TopicSummaryEntity?,
)

/** An item with unpushed changes, with its topic's uid for the document's `topicUid`. */
data class DirtyTopicItem(
    @Embedded val item: TopicItemEntity,
    val topicUid: String,
)

/** A topic document as the cloud holds it (`users/{uid}/topics/{uid}`). */
data class RemoteTopic(
    val uid: String,
    val name: String,
    val purpose: String?,
    val pinned: Boolean,
    /** [dev.kortex.myinfo.topics.domain.model.ItemType] names. */
    val sections: List<String>,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    val summary: RemoteTopicSummary?,
    /** Deleted on another client. Deletes are soft, so the doc stays with this set. */
    val deleted: Boolean,
)

data class RemoteTopicSummary(val text: String, val generatedAtMillis: Long, val fingerprint: String)

/** An item document as the cloud holds it (`users/{uid}/topicItems/{uid}`). */
data class RemoteTopicItem(
    val uid: String,
    val topicUid: String,
    val type: String,
    val addedAtMillis: Long,
    val title: String?,
    val text: String?,
    /** The link's uid in links.db; the caller resolves it to a local id. */
    val linkUid: String?,
    /**
     * Where the item's file sits on the device that added it. Files are never uploaded (there's no
     * Cloud Storage), so on any other device this names a file that isn't there.
     */
    val filePath: String?,
    val mimeType: String?,
    val pageCount: Int?,
    val amountMinor: Long?,
    val currency: String?,
    val issuedAtMillis: Long?,
    val dueAtMillis: Long?,
    val durationSeconds: Int?,
    val readingMinutes: Int?,
    val done: Boolean,
    val pinned: Boolean,
    val messageId: String?,
    val threadId: String?,
    val rfc822MessageId: String?,
    val fromAddress: String?,
    val accountEmail: String?,
    val sentAtMillis: Long?,
    val updatedAtMillis: Long,
    val deleted: Boolean,
)

/** What applying a pull changed outside the database's own rows. */
data class TopicPullResult(
    /** Files of items deleted on another client; left for the caller to remove. */
    val orphanedFiles: List<String>,
    /**
     * Items whose topic or link isn't here yet (docs/CLOUD_SYNC_PLAN.md › Pull). Nothing of them was
     * applied; they have to come again in the next pull.
     */
    val heldBack: List<String> = emptyList(),
)

/**
 * The sync engine's view of topics.db: rows to push and the application of pulled ones.
 * Extends [TopicDao] so pulled deletes cascade and summaries save exactly as the app's own do.
 */
@Dao
abstract class TopicSyncDao : TopicDao() {

    @Transaction
    @Query("SELECT * FROM topics WHERE dirty > 0")
    abstract suspend fun dirtyTopics(): List<DirtyTopic>

    @Query(
        "SELECT topic_items.*, topics.uid AS topicUid FROM topic_items " +
            "JOIN topics ON topics.id = topic_items.topicId WHERE topic_items.dirty > 0",
    )
    abstract suspend fun dirtyItems(): List<DirtyTopicItem>

    /** Topics and items deleted here and not yet pushed as deleted; see [TopicSyncSchema.KIND_TOPIC]. */
    @Query("SELECT * FROM sync_tombstones")
    abstract suspend fun tombstones(): List<SyncTombstoneEntity>

    /**
     * Marks a pushed topic in sync. [dirty] is the count read before the push: if the topic changed
     * again meanwhile, the count moved on and it stays dirty for the next push.
     */
    @Query("UPDATE topics SET dirty = 0 WHERE uid = :uid AND dirty = :dirty")
    abstract suspend fun markTopicPushed(uid: String, dirty: Int)

    /** As [markTopicPushed], for an item. */
    @Query("UPDATE topic_items SET dirty = 0 WHERE uid = :uid AND dirty = :dirty")
    abstract suspend fun markItemPushed(uid: String, dirty: Int)

    /** Drops a pushed tombstone, unless the row was deleted again after it was read. */
    @Query("DELETE FROM sync_tombstones WHERE kind = :kind AND uid = :uid AND deletedAtMillis = :deletedAtMillis")
    abstract suspend fun deleteTombstone(kind: String, uid: String, deletedAtMillis: Long)

    /**
     * Applies pulled topic documents, summaries included, in one transaction with change tracking
     * off. Last writer wins, per [decideRemote]. A name another topic here already has becomes
     * "Name (2)", and that rename is left dirty so it reaches the cloud.
     */
    @Transaction
    open suspend fun applyPulledTopics(topics: List<RemoteTopic>): TopicPullResult {
        setApplying(true)
        val orphanedFiles = mutableListOf<String>()
        for (remote in topics) {
            val local = findTopic(remote.uid)
            val tombstone = if (local == null) findTombstone(TopicSyncSchema.KIND_TOPIC, remote.uid) else null
            val version = local?.let { LocalVersion(it.updatedAtMillis, dirty = it.dirty > 0) }
                ?: tombstone?.let { LocalVersion(it.deletedAtMillis, dirty = true) }

            when (decideRemote(version, remote.updatedAtMillis, remote.deleted)) {
                RemoteAction.Skip -> Unit
                RemoteAction.Delete -> {
                    if (local != null) {
                        orphanedFiles += filePathsOfTopic(local.id)
                        deleteTopic(local.id)
                    }
                    tombstone?.let { deleteTombstone(it.kind, it.uid, it.deletedAtMillis) }
                }
                RemoteAction.Apply -> {
                    val name = uniqueTopicName(remote.name, namesOfOtherTopics(remote.uid))
                    val row = TopicEntity(
                        id = local?.id ?: 0,
                        name = name,
                        purpose = remote.purpose,
                        pinned = remote.pinned,
                        sections = remote.sections.joinToString(","),
                        createdAtMillis = remote.createdAtMillis,
                        updatedAtMillis = remote.updatedAtMillis,
                        uid = remote.uid,
                        dirty = if (name == remote.name) 0 else 1,
                    )
                    val topicId = if (local == null) insertTopic(row) else local.id.also { updatePulledTopic(row) }
                    val summary = remote.summary
                    if (summary == null) {
                        deleteSummary(topicId)
                    } else {
                        upsertSummary(TopicSummaryEntity(topicId, summary.text, summary.generatedAtMillis, summary.fingerprint))
                    }
                    tombstone?.let { deleteTombstone(it.kind, it.uid, it.deletedAtMillis) }
                }
            }
        }
        setApplying(false)
        return TopicPullResult(orphanedFiles = orphanedFiles)
    }

    /**
     * Applies pulled item documents in one transaction with change tracking off. Pull topics first,
     * so the topics these sit in are known. [linkIds] maps link uids to local link ids; an item
     * whose topic or link isn't known yet is held back. An item for a link its topic already holds
     * under another uid (both clients added it) is dropped, as adding it here would be.
     */
    @Transaction
    open suspend fun applyPulledItems(items: List<RemoteTopicItem>, linkIds: Map<String, Long>): TopicPullResult {
        setApplying(true)
        val orphanedFiles = mutableListOf<String>()
        val heldBack = mutableListOf<String>()
        for (remote in items) {
            val local = findItem(remote.uid)
            val tombstone = if (local == null) findTombstone(TopicSyncSchema.KIND_ITEM, remote.uid) else null
            val version = local?.let { LocalVersion(it.updatedAtMillis, dirty = it.dirty > 0) }
                ?: tombstone?.let { LocalVersion(it.deletedAtMillis, dirty = true) }

            when (decideRemote(version, remote.updatedAtMillis, remote.deleted)) {
                RemoteAction.Skip -> Unit
                RemoteAction.Delete -> {
                    if (local != null) {
                        local.filePath?.let(orphanedFiles::add)
                        deleteItemRows(listOf(local.id))
                    }
                    tombstone?.let { deleteTombstone(it.kind, it.uid, it.deletedAtMillis) }
                }
                RemoteAction.Apply -> {
                    val topicId = topicIdOf(remote.topicUid)
                    val linkId = remote.linkUid?.let(linkIds::get)
                    if (topicId == null || (remote.linkUid != null && linkId == null)) {
                        heldBack += remote.uid
                        continue
                    }
                    val row = (local ?: TopicItemEntity(topicId = topicId, type = remote.type, addedAtMillis = remote.addedAtMillis)).copy(
                        topicId = topicId,
                        type = remote.type,
                        addedAtMillis = remote.addedAtMillis,
                        title = remote.title,
                        text = remote.text,
                        linkId = linkId,
                        // A file never changes once added, and another device's path isn't a file here.
                        filePath = local?.filePath ?: remote.filePath,
                        mimeType = remote.mimeType,
                        pageCount = remote.pageCount,
                        amountMinor = remote.amountMinor,
                        currency = remote.currency,
                        issuedAtMillis = remote.issuedAtMillis,
                        dueAtMillis = remote.dueAtMillis,
                        durationSeconds = remote.durationSeconds,
                        readingMinutes = remote.readingMinutes,
                        done = remote.done,
                        pinned = remote.pinned,
                        messageId = remote.messageId,
                        threadId = remote.threadId,
                        rfc822MessageId = remote.rfc822MessageId,
                        fromAddress = remote.fromAddress,
                        accountEmail = remote.accountEmail,
                        sentAtMillis = remote.sentAtMillis,
                        uid = remote.uid,
                        dirty = 0,
                        updatedAtMillis = remote.updatedAtMillis,
                    )
                    // Both ignore the (topic, link) clash rather than fail the pull on it.
                    if (local == null) insertItem(row) else updatePulledItem(row)
                    tombstone?.let { deleteTombstone(it.kind, it.uid, it.deletedAtMillis) }
                }
            }
        }
        setApplying(false)
        return TopicPullResult(orphanedFiles = orphanedFiles, heldBack = heldBack)
    }

    @Query("SELECT COUNT(*) FROM topics")
    abstract suspend fun topicCount(): Int

    /** Local changes the cloud doesn't have yet: edited topics and items, plus unpushed deletes. */
    @Query(UNPUSHED_COUNT)
    abstract suspend fun unpushedCount(): Int

    /** [unpushedCount], again on every change to topics.db: live sync pushes when it rises above 0. */
    @Query(UNPUSHED_COUNT)
    abstract fun observeUnpushedCount(): Flow<Int>

    /**
     * Hands this phone's topics to a newly signed-in account: everything is pushed to it on the next
     * sync, and deletes made under the previous account are dropped rather than sent to this one.
     */
    @Transaction
    open suspend fun adoptForNewAccount() {
        markAllTopicsDirty()
        markAllItemsDirty()
        deleteAllTombstones()
    }

    /**
     * Removes every topic, item and summary from this phone without recording a single delete, so
     * nothing reaches any account's cloud. Returns the files the items held, for the caller to delete.
     */
    @Transaction
    open suspend fun discardAll(): List<String> {
        setApplying(true)
        val files = allFilePaths()
        deleteAllItems()
        deleteAllSummaries()
        deleteAllTopics()
        deleteAllTombstones()
        setApplying(false)
        return files
    }

    // Counts up rather than setting 1, like the triggers, so a push in flight can't mark a row clean.
    @Query("UPDATE topics SET dirty = dirty + 1")
    protected abstract suspend fun markAllTopicsDirty()

    @Query("UPDATE topic_items SET dirty = dirty + 1")
    protected abstract suspend fun markAllItemsDirty()

    @Query("DELETE FROM sync_tombstones")
    protected abstract suspend fun deleteAllTombstones()

    @Query("DELETE FROM topic_items")
    protected abstract suspend fun deleteAllItems()

    @Query("DELETE FROM topic_summaries")
    protected abstract suspend fun deleteAllSummaries()

    @Query("DELETE FROM topics")
    protected abstract suspend fun deleteAllTopics()

    @Query("UPDATE sync_control SET applying = :applying WHERE id = 0")
    protected abstract suspend fun setApplying(applying: Boolean)

    @Query("SELECT * FROM topics WHERE uid = :uid")
    protected abstract suspend fun findTopic(uid: String): TopicEntity?

    @Query("SELECT id FROM topics WHERE uid = :uid")
    protected abstract suspend fun topicIdOf(uid: String): Long?

    @Query("SELECT name FROM topics WHERE uid != :uid")
    protected abstract suspend fun namesOfOtherTopics(uid: String): List<String>

    @Query("SELECT * FROM topic_items WHERE uid = :uid")
    protected abstract suspend fun findItem(uid: String): TopicItemEntity?

    @Query("SELECT * FROM sync_tombstones WHERE kind = :kind AND uid = :uid")
    protected abstract suspend fun findTombstone(kind: String, uid: String): SyncTombstoneEntity?

    @Query("DELETE FROM topic_summaries WHERE topicId = :topicId")
    protected abstract suspend fun deleteSummary(topicId: Long)

    @Update
    protected abstract suspend fun updatePulledTopic(topic: TopicEntity)

    @Update(onConflict = androidx.room.OnConflictStrategy.IGNORE)
    protected abstract suspend fun updatePulledItem(item: TopicItemEntity)
}

private const val UNPUSHED_COUNT =
    "SELECT (SELECT COUNT(*) FROM topics WHERE dirty > 0) + (SELECT COUNT(*) FROM topic_items WHERE dirty > 0) + " +
        "(SELECT COUNT(*) FROM sync_tombstones)"
