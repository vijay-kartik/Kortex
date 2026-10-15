package dev.kortex.sync

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

sealed interface SyncOutcome {
    data object Done : SyncOutcome
    data class Failed(val message: String) : SyncOutcome
}

/** A part of the library that syncs, in pull and push order. */
enum class SyncPartId { Links, Topics, Finance }

/** How far a sync's pull has got, per part of the library. Topics count their items too. */
data class SyncProgress(val parts: Map<SyncPartId, Part>) {
    /** [total] is 0 when that part has nothing to pull. */
    data class Part(val done: Int, val total: Int)

    val links: Part get() = of(SyncPartId.Links)
    val topics: Part get() = of(SyncPartId.Topics)
    val finance: Part get() = of(SyncPartId.Finance)

    val isEmpty: Boolean get() = parts.values.all { it.total == 0 }

    fun of(id: SyncPartId): Part = parts[id] ?: Part(0, 0)
}

/** Links, topics and finances on this phone that belong to another account, found by [CloudSync.otherAccountData] after a sign-in. */
data class OtherAccountData(
    /** Records per part; finances count accounts, entries, recurring payments and categories of yours. */
    val recordCounts: Map<SyncPartId, Int>,
    /** Changes that account never synced; removing its data loses them. */
    val unpushedCount: Int,
    val ownerEmail: String?,
) {
    val linkCount: Int get() = recordCounts[SyncPartId.Links] ?: 0
    val topicCount: Int get() = recordCounts[SyncPartId.Topics] ?: 0
    val financeCount: Int get() = recordCounts[SyncPartId.Finance] ?: 0
}

/**
 * Sync of the signed-in account's links, topics and finances with Firestore
 * (docs/CLOUD_SYNC_PLAN.md, docs/FINANCE_PLAN.md › Firestore): live while the app is on screen
 * ([runLive]), and on demand ([syncNow]). [SyncModule.cloudSync] builds it.
 */
interface CloudSync {
    val syncing: StateFlow<Boolean>

    /** When the signed-in account last finished a sync here; null if it never has. */
    val lastSyncedAt: Flow<Long?>

    /**
     * Pulls, then pushes. Pulling first lets a newer remote change replace a local one before it
     * could be uploaded over it, so the push only carries changes that are the newest anywhere.
     * Links go before topics both ways, since items refer to links; finances, which refer to
     * neither, go last. A second call waits for the running one, then syncs again.
     *
     * [onProgress] hears first with the totals (all 0 when there's nothing to pull, which is how a
     * sign-in tells a new account from one with a library to restore), then after every page.
     */
    suspend fun syncNow(onProgress: (SyncProgress) -> Unit = {}): SyncOutcome

    /**
     * Keeps this phone and the cloud in step until cancelled; the app runs it while it's on screen.
     * Catches up with a full [syncNow] first, then listens to every collection, applying what
     * other clients write as it arrives, and pushes each save as soon as it lands. Does nothing
     * while the phone's data belongs to another account (the sign-in flow settles that first).
     */
    suspend fun runLive()

    /**
     * Pushes local changes now, without pulling. Live sync calls it after edits, and the app when it
     * goes to the background so the last edits don't wait for the next launch.
     * @return false when it couldn't (offline, signed out, or the data isn't this account's).
     */
    suspend fun pushPending(): Boolean

    /**
     * Call right after [user] signs in. Returns null when the links, topics and finances on this phone are
     * already theirs (or there are none, and the phone is simply claimed for them). Otherwise the
     * user chooses between [keepLocalData] and [discardLocalData] before anything syncs.
     */
    suspend fun otherAccountData(user: CloudUser): OtherAccountData?

    /** Keeps this phone's links, topics and finances and makes them [user]'s: the next sync pushes them all to their cloud. */
    suspend fun keepLocalData(user: CloudUser)

    /** Removes this phone's links, topics and finances, leaving every cloud copy as it is, and makes the phone [user]'s. */
    suspend fun discardLocalData(user: CloudUser)

    /** How many links this phone has. */
    suspend fun linkCount(): Int

    /** How many topics this phone has. */
    suspend fun topicCount(): Int

    /** How many accounts and cards this phone has. */
    suspend fun financeAccountCount(): Int

    /** How many expenses, income and other entries this phone has, opening balances aside. */
    suspend fun financeEntryCount(): Int
}
