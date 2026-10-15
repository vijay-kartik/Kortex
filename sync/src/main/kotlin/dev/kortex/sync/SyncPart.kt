package dev.kortex.sync

import kotlinx.coroutines.flow.Flow

/**
 * One part of the library that syncs: its local database and its collections under `users/{uid}/`.
 * [CloudSync] runs every part the same way, in the order it was given them, so a new synced domain
 * is one more part rather than an edit to each sync step.
 */
internal interface SyncPart {
    val id: SyncPartId

    /** Documents written since the last pull, so a restore can show its progress. */
    suspend fun countChanged(userUid: String): Int

    /** Applies every document written since the last pull; [onPage] gets each page's size. */
    suspend fun pull(userUid: String, onPage: (Int) -> Unit)

    /** Writes every local change. */
    suspend fun push(userUid: String)

    /** What live sync listens to: one listener per collection, in apply order. */
    fun listeners(userUid: String): List<LiveListener>

    /** Local changes the cloud doesn't have yet, again on every change: live sync pushes when it rises above 0. */
    fun observeUnpushedCount(): Flow<Int>

    suspend fun unpushedCount(): Int

    /** How many records this phone has, deleted ones aside: 0 means there's nothing to keep or remove. */
    suspend fun recordCount(): Int

    /** Marks everything for push to a newly signed-in account, and drops the old account's deletes. */
    suspend fun adoptForNewAccount()

    /** Removes everything from this phone without recording a delete, so nothing reaches any cloud. */
    suspend fun discardAll()
}

/** A live listener on one collection: [apply] takes in what [changes] heard. */
internal class LiveListener(
    val collection: SyncCollection,
    val changes: Flow<RemoteChanges>,
    val apply: suspend (RemoteChanges) -> Unit,
)
