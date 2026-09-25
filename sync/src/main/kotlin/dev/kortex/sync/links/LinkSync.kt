package dev.kortex.sync.links

import android.util.Log
import com.google.firebase.Timestamp
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.Source
import dev.kortex.links.data.LinkSyncDao
import dev.kortex.links.images.LinkImageSource
import dev.kortex.links.images.LinkImageStore
import dev.kortex.sync.SyncStore
import java.util.Date
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout

/** Moves links between links.db and `users/{uid}/links` (docs/CLOUD_SYNC_PLAN.md › Sync algorithm). */
internal class LinkSync(
    private val firestore: FirebaseFirestore,
    private val dao: LinkSyncDao,
    private val images: LinkImageStore,
    private val store: SyncStore,
) {

    /**
     * Applies every link document written since the last pull, a page at a time. Each page is its
     * own transaction and moves the watermark on, so an interrupted restore resumes where it stopped.
     * Returns how many documents were read.
     */
    suspend fun pull(userUid: String): Int {
        // Server timestamps aren't guaranteed to commit in order, so re-read a little before the
        // watermark. Applying a document twice is harmless: the second time changes nothing.
        val since = (store.linksPulledAt(userUid) - PULL_OVERLAP_MS).coerceAtLeast(0)
        val query = links(userUid)
            .whereGreaterThan(LinkFields.SERVER_UPDATED_AT, Timestamp(Date(since)))
            .orderBy(LinkFields.SERVER_UPDATED_AT)
            .limit(PAGE_SIZE.toLong())

        var read = 0
        var last: DocumentSnapshot? = null
        do {
            // From the server only: a cached answer would make a watermark we can't trust.
            val docs = (last?.let { query.startAfter(it) } ?: query).get(Source.SERVER).await().documents
            if (docs.isEmpty()) break

            val remote = docs.mapNotNull { doc ->
                remoteLink(doc.id, doc.data.orEmpty()).also { if (it == null) Log.w(TAG, "Skipping malformed link doc ${doc.id}") }
            }
            val result = dao.applyPulled(remote)
            result.needsImage.forEach { images.attachWhenReady(it.id, it.url, LinkImageSource.Known(it.imageUrl)) }
            result.deleted.forEach { images.deleteImages(it) }

            docs.last().getTimestamp(LinkFields.SERVER_UPDATED_AT)?.let { store.advanceLinksPulledAt(userUid, it.toDate().time) }
            read += docs.size
            last = docs.last()
        } while (docs.size == PAGE_SIZE)
        return read
    }

    /**
     * Writes every local change: deletes first, so a link deleted and saved again ends up live.
     * A row is marked in sync only once its batch has committed, and only if it hasn't changed
     * again meanwhile. Returns how many documents were written.
     */
    suspend fun push(userUid: String): Int {
        val links = links(userUid)
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

        for (chunk in writes.chunked(BATCH_LIMIT)) {
            val batch = firestore.batch()
            chunk.forEach { batch.set(it.ref, it.data, SetOptions.merge()) }
            // A commit made offline waits for the network instead of failing; don't hang the UI on it.
            withTimeout(COMMIT_TIMEOUT_MS) { batch.commit().await() }
            chunk.forEach { it.onCommitted() }
        }
        return writes.size
    }

    private fun links(userUid: String): CollectionReference =
        firestore.collection("users").document(userUid).collection("links")

    private class PendingWrite(
        val ref: DocumentReference,
        val data: Map<String, Any?>,
        val onCommitted: suspend () -> Unit,
    )

    private companion object {
        const val TAG = "LinkSync"
        const val PAGE_SIZE = 300
        /** Firestore's cap on writes in one batch. */
        const val BATCH_LIMIT = 500
        const val PULL_OVERLAP_MS = 60_000L
        const val COMMIT_TIMEOUT_MS = 30_000L
    }
}
