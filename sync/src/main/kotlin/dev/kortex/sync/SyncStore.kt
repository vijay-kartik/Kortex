package dev.kortex.sync

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.cloudSyncStore by preferencesDataStore(name = "cloud_sync")

/**
 * Sync bookkeeping, kept per account: signing into another account starts from a full pull
 * rather than from where the last one stopped.
 */
internal class SyncStore(context: Context) {

    private val store = context.applicationContext.cloudSyncStore

    /** Server millis of the newest link document pulled; 0 before the first pull. */
    suspend fun linksPulledAt(uid: String): Long = store.data.first()[linksPulledAtKey(uid)] ?: 0L

    /** Only moves forward, since a pull re-reads a little before the watermark. */
    suspend fun advanceLinksPulledAt(uid: String, millis: Long) {
        store.edit { it[linksPulledAtKey(uid)] = maxOf(it[linksPulledAtKey(uid)] ?: 0L, millis) }
    }

    /** Starts the next pull from the beginning. */
    suspend fun resetLinksPulledAt(uid: String) {
        store.edit { it.remove(linksPulledAtKey(uid)) }
    }

    /** The account this phone's links belong to; null on installs from before it was recorded. */
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

    private fun linksPulledAtKey(uid: String) = longPreferencesKey("links_pulled_at:$uid")
    private fun lastSyncedAtKey(uid: String) = longPreferencesKey("last_synced_at:$uid")
}

internal data class LocalOwner(val uid: String, val email: String?)
