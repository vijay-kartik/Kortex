package dev.kortex.sync

import android.content.Context
import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.MemoryCacheSettings
import dev.kortex.links.data.LinkSyncDao
import dev.kortex.links.images.LinkImageStore
import dev.kortex.sync.links.LinkSync
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
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

/** Links on this phone that belong to another account, found when [CloudSync.otherAccountData] runs after a sign-in. */
data class OtherAccountData(
    val linkCount: Int,
    /** Changes that account never synced; removing the links loses them. */
    val unpushedCount: Int,
    val ownerEmail: String?,
)

/**
 * Manual sync of the signed-in account's data with Firestore (docs/CLOUD_SYNC_PLAN.md). Links for
 * now; topics join in phase 4.
 */
class CloudSync(
    context: Context,
    private val account: CloudAccount,
    private val linkDao: LinkSyncDao,
    private val linkImages: LinkImageStore,
) {
    private val store = SyncStore(context)

    // Room is the app's copy; Firestore's own disk cache would only be a third one.
    private val firestore = FirebaseFirestore.getInstance().apply {
        firestoreSettings = FirebaseFirestoreSettings.Builder()
            .setLocalCacheSettings(MemoryCacheSettings.newBuilder().build())
            .build()
    }

    private val links = LinkSync(firestore, linkDao, linkImages, store)
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
     * A second call waits for the running one, then syncs again.
     */
    suspend fun syncNow(): SyncOutcome {
        val user = account.user.value ?: return SyncOutcome.Failed("Sign in to sync.")
        return mutex.withLock {
            // Never push one account's links, or deletes made under it, into another.
            otherAccountDataLocked(user)?.let { other ->
                return@withLock SyncOutcome.Failed(
                    "This phone’s links belong to ${other.ownerEmail ?: "another account"}. Sign out and back in to choose what to do with them.",
                )
            }
            _syncing.value = true
            try {
                links.pull(user.uid)
                links.push(user.uid)
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
     * Call right after [user] signs in. Returns null when the links on this phone are already
     * theirs (or there are none, and the phone is simply claimed for them). Otherwise the user
     * chooses between [keepLocalData] and [discardLocalData] before anything syncs.
     */
    suspend fun otherAccountData(user: CloudUser): OtherAccountData? = mutex.withLock { otherAccountDataLocked(user) }

    /** Keeps this phone's links and makes them [user]'s: the next sync pushes them all to their cloud. */
    suspend fun keepLocalData(user: CloudUser) = mutex.withLock {
        linkDao.adoptForNewAccount()
        claim(user)
    }

    /** Removes this phone's links, leaving every cloud copy as it is, and makes the phone [user]'s. */
    suspend fun discardLocalData(user: CloudUser) = mutex.withLock {
        linkDao.discardAll().forEach { linkImages.deleteImages(it) }
        claim(user)
    }

    private suspend fun otherAccountDataLocked(user: CloudUser): OtherAccountData? {
        val owner = store.localOwner()
        if (owner?.uid == user.uid) return null
        val count = linkDao.linkCount()
        // No owner recorded means links saved before accounts were tracked: they're the signed-in user's.
        if (owner == null || count == 0) {
            if (owner != null) linkDao.adoptForNewAccount() // no links, but maybe the old account's deletes
            claim(user)
            return null
        }
        return OtherAccountData(linkCount = count, unpushedCount = linkDao.unpushedCount(), ownerEmail = owner.email)
    }

    /**
     * Records [user] as the owner of this phone's links. Their pull starts from the beginning: the
     * watermark only holds while this phone still has everything pulled up to it.
     */
    private suspend fun claim(user: CloudUser) {
        store.setLocalOwner(user)
        store.resetLinksPulledAt(user.uid)
    }

    private companion object {
        const val TAG = "CloudSync"
        const val NETWORK_FAILURE = "Couldn’t reach the cloud. Check your connection and try again."
        const val AUTH_FAILURE = "Your account couldn’t be verified. Sign out and back in, then sync again."
        const val GENERIC_FAILURE = "Sync didn’t finish. Try again in a moment."
    }
}
