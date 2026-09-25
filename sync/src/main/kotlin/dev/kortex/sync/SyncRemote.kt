package dev.kortex.sync

import com.google.firebase.Timestamp
import com.google.firebase.firestore.AggregateSource
import android.util.Log
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.Source
import java.util.Date
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout

/** Server time of a document's last write; every synced document carries it, and pulls page through it. */
internal const val SERVER_UPDATED_AT = "serverUpdatedAt"

/**
 * What a listener heard: documents other clients (or this one, confirmed) wrote. [fromServer] is
 * false for an answer from Firestore's memory cache, which mustn't move the watermark.
 */
internal class RemoteChanges(val docs: List<DocumentSnapshot>, val fromServer: Boolean) {
    /** Server time of the newest document here, for the watermark. */
    val newestMillis: Long? get() = docs.mapNotNull { it.getTimestamp(SERVER_UPDATED_AT)?.toDate()?.time }.maxOrNull()
}

/** A document write that, once committed, marks its local row in sync. */
internal class PendingWrite(
    val ref: DocumentReference,
    val data: Map<String, Any?>,
    val onCommitted: suspend () -> Unit,
)

/**
 * The Firestore side of sync, shared by every collection: what changed since the watermark, paging
 * through it, and batched writes (docs/CLOUD_SYNC_PLAN.md › Sync algorithm).
 */
internal class SyncRemote(private val firestore: FirebaseFirestore, private val store: SyncStore) {

    fun collection(userUid: String, collection: SyncCollection): CollectionReference =
        firestore.collection("users").document(userUid).collection(collection.path)

    /** One aggregate read, so a restore can show "84 of 128" rather than a spinner. */
    suspend fun countChanged(userUid: String, collection: SyncCollection): Int =
        changedSince(userUid, collection).count().get(AggregateSource.SERVER).await().count.toInt()

    /**
     * Hands every document written since the watermark to [apply], a page at a time, and moves the
     * watermark on after each page, so an interrupted restore resumes where it stopped.
     */
    suspend fun pullChanged(userUid: String, collection: SyncCollection, apply: suspend (List<DocumentSnapshot>) -> Unit) {
        val query = changedSince(userUid, collection).orderBy(SERVER_UPDATED_AT).limit(PAGE_SIZE.toLong())
        var last: DocumentSnapshot? = null
        do {
            // From the server only: a cached answer would make a watermark we can't trust.
            val docs = (last?.let { query.startAfter(it) } ?: query).get(Source.SERVER).await().documents
            if (docs.isEmpty()) break
            apply(docs)
            docs.last().getTimestamp(SERVER_UPDATED_AT)?.let { store.advancePulledAt(userUid, collection, it.toDate().time) }
            last = docs.last()
        } while (docs.size == PAGE_SIZE)
    }

    /**
     * Every document written to [collection] from the watermark on, as it happens, until the flow is
     * cancelled. The first emission catches up on what's there already. Writes this phone hasn't had
     * confirmed yet are left out; they come again once the server has them. A dropped listener
     * (offline, token refresh) reconnects with backoff.
     */
    fun changes(userUid: String, collection: SyncCollection): Flow<RemoteChanges> = callbackFlow {
        val registration = changedSince(userUid, collection).addSnapshotListener { snapshot, error ->
            if (error != null) {
                close(error)
                return@addSnapshotListener
            }
            if (snapshot == null) return@addSnapshotListener
            val docs = snapshot.documentChanges
                // A document never leaves the query: serverUpdatedAt only grows.
                .filter { it.type != DocumentChange.Type.REMOVED }
                .map { it.document }
                .filter { !it.metadata.hasPendingWrites() }
            if (docs.isNotEmpty()) trySend(RemoteChanges(docs, fromServer = !snapshot.metadata.isFromCache))
        }
        awaitClose { registration.remove() }
    }.retryWhen { cause, attempt ->
        Log.w(TAG, "Listener on ${collection.path} dropped; reconnecting", cause)
        delay((LISTEN_RETRY_MS shl attempt.toInt().coerceAtMost(6)).coerceAtMost(LISTEN_RETRY_MAX_MS))
        true
    }

    /** The documents among [ids] that exist, read from the server. */
    suspend fun fetch(userUid: String, collection: SyncCollection, ids: Collection<String>): List<DocumentSnapshot> {
        val docs = collection(userUid, collection)
        return ids.map { docs.document(it).get(Source.SERVER).await() }.filter { it.exists() }
    }

    /** Merges each write over its document, in batches, marking rows in sync as their batch commits. */
    suspend fun write(writes: List<PendingWrite>) {
        for (chunk in writes.chunked(BATCH_LIMIT)) {
            val batch = firestore.batch()
            chunk.forEach { batch.set(it.ref, it.data, SetOptions.merge()) }
            // A commit made offline waits for the network instead of failing; don't hang the UI on it.
            withTimeout(COMMIT_TIMEOUT_MS) { batch.commit().await() }
            chunk.forEach { it.onCommitted() }
        }
    }

    private suspend fun changedSince(userUid: String, collection: SyncCollection): Query {
        // Server timestamps aren't guaranteed to commit in order, so re-read a little before the
        // watermark. Applying a document twice is harmless: the second time changes nothing.
        val since = (store.pulledAt(userUid, collection) - PULL_OVERLAP_MS).coerceAtLeast(0)
        return collection(userUid, collection).whereGreaterThan(SERVER_UPDATED_AT, Timestamp(Date(since)))
    }

    private companion object {
        const val TAG = "SyncRemote"
        const val LISTEN_RETRY_MS = 2_000L
        const val LISTEN_RETRY_MAX_MS = 60_000L
        const val PAGE_SIZE = 300
        /** Firestore's cap on writes in one batch. */
        const val BATCH_LIMIT = 500
        const val PULL_OVERLAP_MS = 60_000L
        const val COMMIT_TIMEOUT_MS = 30_000L
    }
}
