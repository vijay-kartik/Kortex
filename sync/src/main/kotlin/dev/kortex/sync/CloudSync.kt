package dev.kortex.sync

import android.content.Context
import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.MemoryCacheSettings
import dev.kortex.links.data.LinkSyncDao
import dev.kortex.links.images.LinkImageStore
import dev.kortex.myinfo.topics.data.local.TopicSyncDao
import dev.kortex.myinfo.topics.domain.port.FileVault
import dev.kortex.sync.links.LinkSync
import dev.kortex.sync.topics.TopicSync
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

sealed interface SyncOutcome {
    data object Done : SyncOutcome
    data class Failed(val message: String) : SyncOutcome
}

/** How far a sync's pull has got, per part of the library. Topics count their items too. */
data class SyncProgress(val links: Part, val topics: Part) {
    /** [total] is 0 when that part has nothing to pull. */
    data class Part(val done: Int, val total: Int)

    val isEmpty: Boolean get() = links.total == 0 && topics.total == 0
}

/** Links and topics on this phone that belong to another account, found by [CloudSync.otherAccountData] after a sign-in. */
data class OtherAccountData(
    val linkCount: Int,
    val topicCount: Int,
    /** Changes that account never synced; removing its data loses them. */
    val unpushedCount: Int,
    val ownerEmail: String?,
)

/**
 * Sync of the signed-in account's links and topics with Firestore (docs/CLOUD_SYNC_PLAN.md): live
 * while the app is on screen ([runLive]), and on demand ([syncNow]).
 */
class CloudSync(
    context: Context,
    private val account: CloudAccount,
    private val linkDao: LinkSyncDao,
    private val linkImages: LinkImageStore,
    private val topicDao: TopicSyncDao,
    private val topicFiles: FileVault,
) {
    private val store = SyncStore(context)

    // Room is the app's copy; Firestore's own disk cache would only be a third one.
    private val firestore = FirebaseFirestore.getInstance().apply {
        firestoreSettings = FirebaseFirestoreSettings.Builder()
            .setLocalCacheSettings(MemoryCacheSettings.newBuilder().build())
            .build()
    }

    private val remote = SyncRemote(firestore, store)
    private val links = LinkSync(remote, linkDao, linkImages)
    private val topics = TopicSync(remote, store, topicDao, linkDao, topicFiles)
    private val mutex = Mutex()

    private val _syncing = MutableStateFlow(false)
    val syncing: StateFlow<Boolean> = _syncing.asStateFlow()

    /** When the signed-in account last finished a sync here; null if it never has. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val lastSyncedAt: Flow<Long?> = account.user.flatMapLatest { user ->
        user?.let { store.lastSyncedAt(it.uid) } ?: flowOf(null)
    }

    /**
     * Pulls, then pushes. Pulling first lets a newer remote change replace a local one before it
     * could be uploaded over it, so the push only carries changes that are the newest anywhere.
     * Links go before topics both ways, since items refer to links. A second call waits for the
     * running one, then syncs again.
     *
     * [onProgress] hears first with the totals (all 0 when there's nothing to pull, which is how a
     * sign-in tells a new account from one with a library to restore), then after every page.
     */
    suspend fun syncNow(onProgress: (SyncProgress) -> Unit = {}): SyncOutcome {
        val user = account.user.value ?: return SyncOutcome.Failed("Sign in to sync.")
        return mutex.withLock {
            // Never push one account's data, or deletes made under it, into another.
            otherAccountDataLocked(user)?.let { other ->
                return@withLock SyncOutcome.Failed(
                    "This phone’s links and topics belong to ${other.ownerEmail ?: "another account"}. " +
                        "Sign out and back in to choose what to do with them.",
                )
            }
            _syncing.value = true
            try {
                var progress = SyncProgress(
                    links = SyncProgress.Part(0, links.countChanged(user.uid)),
                    topics = SyncProgress.Part(0, topics.countChanged(user.uid)),
                )
                onProgress(progress)
                // Documents written since the count can push a part past its total.
                links.pull(user.uid) { read ->
                    val part = progress.links
                    progress = progress.copy(links = part.copy(done = (part.done + read).coerceAtMost(part.total)))
                    onProgress(progress)
                }
                topics.pull(user.uid) { read ->
                    val part = progress.topics
                    progress = progress.copy(topics = part.copy(done = (part.done + read).coerceAtMost(part.total)))
                    onProgress(progress)
                }
                links.push(user.uid)
                topics.push(user.uid)
                store.setLastSyncedAt(user.uid, System.currentTimeMillis())
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

    /**
     * Keeps this phone and the cloud in step until cancelled; the app runs it while it's on screen.
     * Catches up with a full [syncNow] first, then listens to all three collections, applying what
     * other clients write as it arrives, and pushes each save as soon as it lands. Does nothing while the phone's data belongs to another account (the sign-in flow settles
     * that first).
     */
    suspend fun runLive() {
        val user = account.user.value ?: return
        if (otherAccountData(user) != null) return
        // A failed catch-up is fine: the listeners and the push loop retry on their own.
        syncNow()
        coroutineScope {
            launch {
                links.changes(user.uid).collect { changes ->
                    applyHeard(user, SyncCollection.Links, changes) {
                        links.apply(changes.docs)
                        // An item may have been waiting for one of these links.
                        topics.retryHeldItems(user.uid)
                    }
                }
            }
            launch {
                topics.topicChanges(user.uid).collect { changes ->
                    applyHeard(user, SyncCollection.Topics, changes) {
                        topics.applyTopics(changes.docs)
                        topics.retryHeldItems(user.uid)
                    }
                }
            }
            launch {
                topics.itemChanges(user.uid).collect { changes ->
                    applyHeard(user, SyncCollection.TopicItems, changes) { topics.applyHeardItems(user.uid, changes.docs) }
                }
            }
            launch { pushWhenChanged(user) }
        }
    }

    /**
     * Pushes local changes now, without pulling. Live sync calls it after edits, and the app when it
     * goes to the background so the last edits don't wait for the next launch.
     * @return false when it couldn't (offline, signed out, or the data isn't this account's).
     */
    suspend fun pushPending(): Boolean {
        val user = account.user.value ?: return false
        return mutex.withLock {
            if (store.localOwner()?.uid != user.uid) return@withLock false
            try {
                links.push(user.uid)
                topics.push(user.uid)
                store.setLastSyncedAt(user.uid, System.currentTimeMillis())
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
            if (store.localOwner()?.uid != user.uid || account.user.value?.uid != user.uid) return@withLock
            try {
                apply()
                if (changes.fromServer) changes.newestMillis?.let { store.advancePulledAt(user.uid, collection, it) }
                store.setLastSyncedAt(user.uid, System.currentTimeMillis())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The watermark didn't move, so the next catch-up pull brings these again.
                Log.w(TAG, "Couldn't apply changes to ${collection.path}", e)
            }
        }
    }

    /**
     * Pushes whenever a save leaves links.db or topics.db with unpushed changes: saving a link,
     * adding an item, creating or editing a topic, deleting. A failed push is retried with backoff;
     * the next save also tries again.
     */
    @OptIn(FlowPreview::class)
    private suspend fun pushWhenChanged(user: CloudUser) {
        combine(linkDao.observeUnpushedCount(), topicDao.observeUnpushedCount()) { links, topics -> links + topics }
            .filter { it > 0 }
            .debounce(PUSH_DEBOUNCE_MS)
            .conflate()
            .collect {
                var wait = PUSH_RETRY_MS
                while (account.user.value?.uid == user.uid && !pushPending()) {
                    delay(wait)
                    wait = (wait * 2).coerceAtMost(PUSH_RETRY_MAX_MS)
                }
            }
    }

    /** How many links this phone has. */
    suspend fun linkCount(): Int = linkDao.linkCount()

    /** How many topics this phone has. */
    suspend fun topicCount(): Int = topicDao.topicCount()

    /**
     * Call right after [user] signs in. Returns null when the links and topics on this phone are
     * already theirs (or there are none, and the phone is simply claimed for them). Otherwise the
     * user chooses between [keepLocalData] and [discardLocalData] before anything syncs.
     */
    suspend fun otherAccountData(user: CloudUser): OtherAccountData? = mutex.withLock { otherAccountDataLocked(user) }

    /** Keeps this phone's links and topics and makes them [user]'s: the next sync pushes them all to their cloud. */
    suspend fun keepLocalData(user: CloudUser) = mutex.withLock {
        adoptLocalData()
        claim(user)
    }

    /** Removes this phone's links and topics, leaving every cloud copy as it is, and makes the phone [user]'s. */
    suspend fun discardLocalData(user: CloudUser) = mutex.withLock {
        linkDao.discardAll().forEach { linkImages.deleteImages(it) }
        topicFiles.delete(topicDao.discardAll())
        claim(user)
    }

    private suspend fun otherAccountDataLocked(user: CloudUser): OtherAccountData? {
        val owner = store.localOwner()
        if (owner?.uid == user.uid) return null
        val linkCount = linkDao.linkCount()
        val topicCount = topicDao.topicCount()
        // No owner recorded means data saved before accounts were tracked: it's the signed-in user's.
        if (owner == null || linkCount + topicCount == 0) {
            if (owner != null) adoptLocalData() // nothing left, but maybe the old account's deletes
            claim(user)
            return null
        }
        return OtherAccountData(
            linkCount = linkCount,
            topicCount = topicCount,
            unpushedCount = linkDao.unpushedCount() + topicDao.unpushedCount(),
            ownerEmail = owner.email,
        )
    }

    private suspend fun adoptLocalData() {
        linkDao.adoptForNewAccount()
        topicDao.adoptForNewAccount()
    }

    /**
     * Records [user] as the owner of this phone's data. Their pull starts from the beginning: a
     * watermark only holds while this phone still has everything pulled up to it.
     */
    private suspend fun claim(user: CloudUser) {
        store.setLocalOwner(user)
        store.resetPulledAt(user.uid)
    }

    private companion object {
        const val TAG = "CloudSync"
        /**
         * Rows only turn dirty when something is saved (a form being filled in writes nothing), so
         * this just lets saves that land together — a multi-select move, a delete's cascade — go
         * up as one push.
         */
        const val PUSH_DEBOUNCE_MS = 300L
        const val PUSH_RETRY_MS = 5_000L
        const val PUSH_RETRY_MAX_MS = 5 * 60_000L
        const val NETWORK_FAILURE = "Couldn’t reach the cloud. Check your connection and try again."
        const val AUTH_FAILURE = "Your account couldn’t be verified. Sign out and back in, then sync again."
        const val GENERIC_FAILURE = "Sync didn’t finish. Try again in a moment."
    }
}
