package dev.kortex.sync

import android.util.Log
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What this phone holds, for the summary after a restore. */
internal interface LibraryCounts {
    suspend fun links(): Int
    suspend fun topics(): Int
    suspend fun financeAccounts(): Int
    suspend fun financeEntries(): Int
}

/**
 * [CloudSync] over [parts], each run in turn: links, then topics, then finance (see [syncNow]).
 * [signedIn] is the signed-in account; only its data ever syncs.
 */
internal class FirestoreCloudSync(
    private val signedIn: StateFlow<CloudUser?>,
    private val store: SyncStore,
    private val parts: List<SyncPart>,
    private val counts: LibraryCounts,
    private val now: () -> Long = System::currentTimeMillis,
) : CloudSync {

    private val mutex = Mutex()

    private val _syncing = MutableStateFlow(false)
    override val syncing: StateFlow<Boolean> = _syncing.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    override val lastSyncedAt: Flow<Long?> = signedIn.flatMapLatest { user ->
        user?.let { store.lastSyncedAt(it.uid) } ?: flowOf(null)
    }

    override suspend fun syncNow(onProgress: (SyncProgress) -> Unit): SyncOutcome {
        val user = signedIn.value ?: return SyncOutcome.Failed("Sign in to sync.")
        return mutex.withLock {
            // Never push one account's data, or deletes made under it, into another.
            otherAccountDataLocked(user)?.let { other ->
                return@withLock SyncOutcome.Failed(
                    "This phone’s links, topics and finances belong to ${other.ownerEmail ?: "another account"}. " +
                        "Sign out and back in to choose what to do with them.",
                )
            }
            _syncing.value = true
            try {
                var progress = SyncProgress(parts.associate { it.id to SyncProgress.Part(0, it.countChanged(user.uid)) })
                onProgress(progress)
                for (part in parts) {
                    part.pull(user.uid) { read ->
                        // Documents written since the count can push a part past its total.
                        val done = progress.of(part.id)
                        progress = SyncProgress(
                            progress.parts + (part.id to done.copy(done = (done.done + read).coerceAtMost(done.total))),
                        )
                        onProgress(progress)
                    }
                }
                parts.forEach { it.push(user.uid) }
                store.setLastSyncedAt(user.uid, now())
                SyncOutcome.Done
            } catch (e: TimeoutCancellationException) {
                SyncOutcome.Failed(NETWORK_FAILURE)
            } catch (e: CancellationException) {
                throw e
            } catch (e: FirebaseFirestoreException) {
                Log.w(TAG, "Sync failed", e)
                SyncOutcome.Failed(
                    when (e.code) {
                        FirebaseFirestoreException.Code.UNAVAILABLE,
                        FirebaseFirestoreException.Code.DEADLINE_EXCEEDED -> NETWORK_FAILURE
                        FirebaseFirestoreException.Code.PERMISSION_DENIED,
                        FirebaseFirestoreException.Code.UNAUTHENTICATED -> AUTH_FAILURE
                        else -> GENERIC_FAILURE
                    },
                )
            } catch (e: Exception) {
                Log.w(TAG, "Sync failed", e)
                SyncOutcome.Failed(GENERIC_FAILURE)
            } finally {
                _syncing.value = false
            }
        }
    }

    override suspend fun runLive() {
        val user = signedIn.value ?: return
        if (otherAccountData(user) != null) return
        // A failed catch-up is fine: the listeners and the push loop retry on their own.
        syncNow()
        coroutineScope {
            for (listener in parts.flatMap { it.listeners(user.uid) }) {
                launch {
                    listener.changes.collect { changes ->
                        applyHeard(user, listener.collection, changes) { listener.apply(changes) }
                    }
                }
            }
            launch { pushWhenChanged(user) }
        }
    }

    override suspend fun pushPending(): Boolean {
        val user = signedIn.value ?: return false
        return mutex.withLock {
            if (store.localOwner()?.uid != user.uid) return@withLock false
            try {
                parts.forEach { it.push(user.uid) }
                store.setLastSyncedAt(user.uid, now())
                true
            } catch (e: TimeoutCancellationException) {
                false
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Push failed", e)
                false
            }
        }
    }

    /** Applies what a listener heard, then moves that collection's watermark past it. */
    private suspend fun applyHeard(user: CloudUser, collection: SyncCollection, changes: RemoteChanges, apply: suspend () -> Unit) {
        mutex.withLock {
            // Signed out, or into another account, while this was queued.
            if (store.localOwner()?.uid != user.uid || signedIn.value?.uid != user.uid) return@withLock
            try {
                apply()
                if (changes.fromServer) changes.newestMillis?.let { store.advancePulledAt(user.uid, collection, it) }
                store.setLastSyncedAt(user.uid, now())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The watermark didn't move, so the next catch-up pull brings these again.
                Log.w(TAG, "Couldn't apply changes to ${collection.path}", e)
            }
        }
    }

    /**
     * Pushes whenever a save leaves any part with unpushed changes: saving a link, adding an item,
     * creating or editing a topic, an expense saved, deleting. A failed push is retried with backoff;
     * the next save also tries again.
     */
    @OptIn(FlowPreview::class)
    private suspend fun pushWhenChanged(user: CloudUser) {
        combine(parts.map { it.observeUnpushedCount() }) { it.sum() }
            .filter { it > 0 }
            .debounce(PUSH_DEBOUNCE_MS)
            .conflate()
            .collect {
                var wait = PUSH_RETRY_MS
                while (signedIn.value?.uid == user.uid && !pushPending()) {
                    delay(wait)
                    wait = (wait * 2).coerceAtMost(PUSH_RETRY_MAX_MS)
                }
            }
    }

    override suspend fun linkCount(): Int = counts.links()

    override suspend fun topicCount(): Int = counts.topics()

    override suspend fun financeAccountCount(): Int = counts.financeAccounts()

    override suspend fun financeEntryCount(): Int = counts.financeEntries()

    override suspend fun otherAccountData(user: CloudUser): OtherAccountData? = mutex.withLock { otherAccountDataLocked(user) }

    override suspend fun keepLocalData(user: CloudUser) = mutex.withLock {
        adoptLocalData()
        claim(user)
    }

    override suspend fun discardLocalData(user: CloudUser) = mutex.withLock {
        parts.forEach { it.discardAll() }
        claim(user)
    }

    private suspend fun otherAccountDataLocked(user: CloudUser): OtherAccountData? {
        val owner = store.localOwner()
        if (owner?.uid == user.uid) return null
        val recordCounts = parts.associate { it.id to it.recordCount() }
        // No owner recorded means data saved before accounts were tracked: it's the signed-in user's.
        if (owner == null || recordCounts.values.sum() == 0) {
            if (owner != null) adoptLocalData() // nothing left, but maybe the old account's deletes
            claim(user)
            return null
        }
        return OtherAccountData(
            recordCounts = recordCounts,
            unpushedCount = parts.sumOf { it.unpushedCount() },
            ownerEmail = owner.email,
        )
    }

    private suspend fun adoptLocalData() {
        parts.forEach { it.adoptForNewAccount() }
    }

    /**
     * Records [user] as the owner of this phone's data. Their pull starts from the beginning: a
     * watermark only holds while this phone still has everything pulled up to it.
     */
    private suspend fun claim(user: CloudUser) {
        store.setLocalOwner(user)
        store.resetPulledAt(user.uid)
    }

    internal companion object {
        private const val TAG = "CloudSync"
        /**
         * Rows only turn dirty when something is saved (a form being filled in writes nothing), so
         * this just lets saves that land together — a multi-select move, a delete's cascade — go
         * up as one push.
         */
        private const val PUSH_DEBOUNCE_MS = 300L
        private const val PUSH_RETRY_MS = 5_000L
        private const val PUSH_RETRY_MAX_MS = 5 * 60_000L
        const val NETWORK_FAILURE = "Couldn’t reach the cloud. Check your connection and try again."
        const val AUTH_FAILURE = "Your account couldn’t be verified. Sign out and back in, then sync again."
        const val GENERIC_FAILURE = "Sync didn’t finish. Try again in a moment."
    }
}
