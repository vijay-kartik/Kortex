package dev.kortex.sync

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.cloudSyncStore by preferencesDataStore(name = "cloud_sync")

/** A synced Firestore collection under `users/{uid}/`, each pulled from its own watermark. */
internal enum class SyncCollection(val path: String) {
    Links("links"),
    Topics("topics"),
    TopicItems("topicItems"),
}

/**
 * Sync bookkeeping, kept per account: signing into another account starts from a full pull
 * rather than from where the last one stopped.
 */
internal class SyncStore(context: Context) {

    private val store = context.applicationContext.cloudSyncStore

    /** Server millis of the newest document pulled from [collection]; 0 before the first pull. */
    suspend fun pulledAt(uid: String, collection: SyncCollection): Long =
        store.data.first()[pulledAtKey(uid, collection)] ?: 0L

    /** Only moves forward, since a pull re-reads a little before the watermark. */
    suspend fun advancePulledAt(uid: String, collection: SyncCollection, millis: Long) {
        val key = pulledAtKey(uid, collection)
        store.edit { it[key] = maxOf(it[key] ?: 0L, millis) }
    }

    /** Starts the next pull of every collection from the beginning, and forgets held-back items. */
    suspend fun resetPulledAt(uid: String) {
        store.edit { prefs ->
            SyncCollection.entries.forEach { prefs.remove(pulledAtKey(uid, it)) }
            prefs.remove(heldItemsKey(uid))
        }
    }

    /**
     * Item documents pulled before their topic or link was here. The watermark has moved past
     * them, so the next pull fetches them again by id.
     */
    suspend fun heldItems(uid: String): Set<String> = store.data.first()[heldItemsKey(uid)].orEmpty()

    suspend fun setHeldItems(uid: String, itemUids: Set<String>) {
        store.edit { if (itemUids.isEmpty()) it.remove(heldItemsKey(uid)) else it[heldItemsKey(uid)] = itemUids }
    }

    /** The account this phone's links and topics belong to; null on installs from before it was recorded. */
    suspend fun localOwner(): LocalOwner? {
        val prefs = store.data.first()
        val uid = prefs[OWNER_UID] ?: return null
        return LocalOwner(uid, prefs[OWNER_EMAIL])
    }

    suspend fun setLocalOwner(user: CloudUser) {
        store.edit {
            it[OWNER_UID] = user.uid
            if (user.email != null) it[OWNER_EMAIL] = user.email else it.remove(OWNER_EMAIL)
        }
    }

    fun lastSyncedAt(uid: String): Flow<Long?> = store.data.map { it[lastSyncedAtKey(uid)] }

    suspend fun setLastSyncedAt(uid: String, millis: Long) {
        store.edit { it[lastSyncedAtKey(uid)] = millis }
    }

    private val OWNER_UID = stringPreferencesKey("local_owner_uid")
    private val OWNER_EMAIL = stringPreferencesKey("local_owner_email")

    // "links_pulled_at:<uid>" is the key phase 3 wrote, so existing watermarks carry over.
    private fun pulledAtKey(uid: String, collection: SyncCollection) =
        longPreferencesKey("${collection.path.lowercase()}_pulled_at:$uid")
    private fun heldItemsKey(uid: String) = stringSetPreferencesKey("held_items:$uid")
    private fun lastSyncedAtKey(uid: String) = longPreferencesKey("last_synced_at:$uid")
}

internal data class LocalOwner(val uid: String, val email: String?)
