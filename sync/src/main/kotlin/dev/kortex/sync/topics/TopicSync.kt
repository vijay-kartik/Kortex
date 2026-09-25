package dev.kortex.sync.topics

import android.util.Log
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import dev.kortex.links.data.LinkSyncDao
import dev.kortex.myinfo.topics.data.local.SyncTombstoneEntity
import dev.kortex.myinfo.topics.data.local.TopicSyncDao
import dev.kortex.myinfo.topics.domain.port.FileVault
import dev.kortex.sync.PendingWrite
import dev.kortex.sync.RemoteChanges
import dev.kortex.sync.SyncCollection
import dev.kortex.sync.SyncRemote
import dev.kortex.sync.SyncStore
import kotlinx.coroutines.flow.Flow

/**
 * Moves topics, their summaries and their items between topics.db and `users/{uid}/topics` and
 * `users/{uid}/topicItems` (docs/CLOUD_SYNC_PLAN.md › Sync algorithm). Records only: an item's file
 * stays on the device that added it.
 */
internal class TopicSync(
    private val remote: SyncRemote,
    private val store: SyncStore,
    private val dao: TopicSyncDao,
    private val linkDao: LinkSyncDao,
    private val files: FileVault,
) {

    /** Topic and item documents written since the last pull. */
    suspend fun countChanged(userUid: String): Int =
        remote.countChanged(userUid, SyncCollection.Topics) + remote.countChanged(userUid, SyncCollection.TopicItems)

    /**
     * Applies every topic document, then every item document, written since the last pull; [onPage]
     * gets each page's size. Pull links first: items resolve their link uids against links.db.
     */
    suspend fun pull(userUid: String, onPage: (Int) -> Unit) {
        remote.pullChanged(userUid, SyncCollection.Topics) { docs ->
            applyTopics(docs)
            onPage(docs.size)
        }

        // Items held back last time are past the watermark, so they're fetched again by id.
        val held = mutableSetOf<String>()
        val heldBefore = store.heldItems(userUid)
        if (heldBefore.isNotEmpty()) held += applyItems(remote.fetch(userUid, SyncCollection.TopicItems, heldBefore))
        remote.pullChanged(userUid, SyncCollection.TopicItems) { docs ->
            // An item held back above and applied in a page here is no longer held.
            val pageUids = docs.map { it.id }.toSet()
            held.removeAll(pageUids)
            held += applyItems(docs)
            onPage(docs.size)
        }
        store.setHeldItems(userUid, held)
    }

    /** Topic documents as other clients write them, for live sync. */
    fun topicChanges(userUid: String): Flow<RemoteChanges> = remote.changes(userUid, SyncCollection.Topics)

    /** Item documents as other clients write them, for live sync. */
    fun itemChanges(userUid: String): Flow<RemoteChanges> = remote.changes(userUid, SyncCollection.TopicItems)

    /** Applies pulled or heard topic documents, summaries included, and deletes the files deleted topics held. */
    suspend fun applyTopics(docs: List<DocumentSnapshot>) {
        val topics = docs.mapNotNull { doc ->
            remoteTopic(doc.id, doc.data.orEmpty()).also { if (it == null) Log.w(TAG, "Skipping malformed topic doc ${doc.id}") }
        }
        files.delete(dao.applyPulledTopics(topics).orphanedFiles)
    }

    /**
     * Live sync's item apply: items heard before their topic or link join the held-back set, and
     * items heard now come out of it.
     */
    suspend fun applyHeardItems(userUid: String, docs: List<DocumentSnapshot>) {
        val heldBack = applyItems(docs)
        store.setHeldItems(userUid, store.heldItems(userUid) - docs.map { it.id }.toSet() + heldBack)
    }

    /** Tries held-back items again, once a topic or link they wait for may have arrived. */
    suspend fun retryHeldItems(userUid: String) {
        val held = store.heldItems(userUid)
        if (held.isEmpty()) return
        // Documents gone from the cloud drop out with the rest that could be applied.
        store.setHeldItems(userUid, applyItems(remote.fetch(userUid, SyncCollection.TopicItems, held)).toSet())
    }

    /** Applies pulled item documents; returns the uids held back until their topic or link is here. */
    private suspend fun applyItems(docs: List<DocumentSnapshot>): List<String> {
        val items = docs.mapNotNull { doc ->
            remoteTopicItem(doc.id, doc.data.orEmpty()).also { if (it == null) Log.w(TAG, "Skipping malformed item doc ${doc.id}") }
        }
        if (items.isEmpty()) return emptyList()
        val linkUids = items.mapNotNull { it.linkUid }.distinct()
        val linkIds = if (linkUids.isEmpty()) emptyMap() else linkDao.idsOf(linkUids).associate { it.uid to it.id }
        val result = dao.applyPulledItems(items, linkIds)
        files.delete(result.orphanedFiles)
        return result.heldBack
    }

    /**
     * Writes every local change: deletes, then topics, then items, so an item's topic is in the
     * cloud before it is. A row is marked in sync only once its batch has committed, and only if it
     * hasn't changed again meanwhile.
     */
    suspend fun push(userUid: String) {
        val topics = remote.collection(userUid, SyncCollection.Topics)
        val items = remote.collection(userUid, SyncCollection.TopicItems)
        val now = FieldValue.serverTimestamp()

        val deletes = dao.tombstones().map { tombstone ->
            val collection = if (tombstone.kind == SyncTombstoneEntity.KIND_TOPIC) topics else items
            PendingWrite(collection.document(tombstone.uid), deletedDoc(tombstone.deletedAtMillis, now)) {
                dao.deleteTombstone(tombstone.kind, tombstone.uid, tombstone.deletedAtMillis)
            }
        }
        val topicWrites = dao.dirtyTopics().map { dirty ->
            PendingWrite(topics.document(dirty.topic.uid), topicDoc(dirty, now)) {
                dao.markTopicPushed(dirty.topic.uid, dirty.topic.dirty)
            }
        }
        val dirtyItems = dao.dirtyItems()
        val linkIds = dirtyItems.mapNotNull { it.item.linkId }.distinct()
        val linkUids = if (linkIds.isEmpty()) emptyMap() else linkDao.uidsOf(linkIds).associate { it.id to it.uid }
        val itemWrites = dirtyItems.mapNotNull { dirty ->
            val item = dirty.item
            val linkUid = item.linkId?.let { linkUids[it] }
            if (item.linkId != null && linkUid == null) {
                // Its link is gone from the library; another device couldn't resolve it. Left dirty.
                Log.w(TAG, "Not pushing item ${item.uid}: link ${item.linkId} isn't in the library")
                return@mapNotNull null
            }
            PendingWrite(items.document(item.uid), itemDoc(item, dirty.topicUid, linkUid, now)) {
                dao.markItemPushed(item.uid, item.dirty)
            }
        }
        remote.write(deletes + topicWrites + itemWrites)
    }

    private companion object {
        const val TAG = "TopicSync"
    }
}
