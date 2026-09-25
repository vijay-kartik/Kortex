package dev.kortex.sync.links

import android.util.Log
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import dev.kortex.links.data.LinkSyncDao
import dev.kortex.links.images.LinkImageSource
import dev.kortex.links.images.LinkImageStore
import dev.kortex.sync.PendingWrite
import dev.kortex.sync.RemoteChanges
import dev.kortex.sync.SyncCollection
import dev.kortex.sync.SyncRemote
import kotlinx.coroutines.flow.Flow

/** Moves links between links.db and `users/{uid}/links` (docs/CLOUD_SYNC_PLAN.md › Sync algorithm). */
internal class LinkSync(
    private val remote: SyncRemote,
    private val dao: LinkSyncDao,
    private val images: LinkImageStore,
) {

    /** Link documents written since the last pull. */
    suspend fun countChanged(userUid: String): Int = remote.countChanged(userUid, SyncCollection.Links)

    /** Applies every link document written since the last pull; [onPage] gets each page's size. */
    suspend fun pull(userUid: String, onPage: (Int) -> Unit) {
        remote.pullChanged(userUid, SyncCollection.Links) { docs ->
            apply(docs)
            onPage(docs.size)
        }
    }

    /** Link documents as other clients write them, for live sync. */
    fun changes(userUid: String): Flow<RemoteChanges> = remote.changes(userUid, SyncCollection.Links)

    /** Applies pulled or heard link documents, and fetches or drops thumbnails to match. */
    suspend fun apply(docs: List<DocumentSnapshot>) {
        val links = docs.mapNotNull { doc ->
            remoteLink(doc.id, doc.data.orEmpty()).also { if (it == null) Log.w(TAG, "Skipping malformed link doc ${doc.id}") }
        }
        val result = dao.applyPulled(links)
        result.needsImage.forEach { images.attachWhenReady(it.id, it.url, LinkImageSource.Known(it.imageUrl)) }
        result.deleted.forEach { images.deleteImages(it) }
    }

    /**
     * Writes every local change: deletes first, so a link deleted and saved again ends up live.
     * A row is marked in sync only once its batch has committed, and only if it hasn't changed
     * again meanwhile.
     */
    suspend fun push(userUid: String) {
        val links = remote.collection(userUid, SyncCollection.Links)
        val now = FieldValue.serverTimestamp()
        val writes = dao.tombstones().map { tombstone ->
            PendingWrite(links.document(tombstone.uid), deletedLinkDoc(tombstone.deletedAtMillis, now)) {
                dao.deleteTombstone(tombstone.kind, tombstone.uid, tombstone.deletedAtMillis)
            }
        } + dao.dirtyLinks().map { link ->
            PendingWrite(links.document(link.link.uid), linkDoc(link, now)) {
                dao.markPushed(link.link.uid, link.link.dirty)
            }
        }
        remote.write(writes)
    }

    private companion object {
        const val TAG = "LinkSync"
    }
}
