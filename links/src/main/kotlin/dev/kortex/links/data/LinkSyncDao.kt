package dev.kortex.links.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update

/** A link document as the cloud holds it (`users/{uid}/links/{uid}`). */
data class RemoteLink(
    val uid: String,
    val url: String,
    val title: String,
    val tags: List<String>,
    val imageUrl: String?,
    val imageHidden: Boolean,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    /** Deleted on another client. Deletes are soft, so the doc stays with this set. */
    val deleted: Boolean,
)

/** What applying a pull changed outside the database's own rows. */
data class LinkPullResult(
    /** Links whose thumbnail has to be downloaded: new here, or given a different image. */
    val needsImage: List<Long>,
    /** Links deleted on another client; their thumbnails are left for the caller to remove. */
    val deleted: List<Long>,
)

data class LinkIdUid(val id: Long, val uid: String)

/**
 * The sync engine's view of links.db: rows to push and the application of pulled ones.
 * Extends [LinkDao] so pulled tags and deletes go through the same tag bookkeeping as the app's own.
 */
@Dao
abstract class LinkSyncDao : LinkDao() {

    /** Links with unpushed changes, with their tags. */
    @Transaction
    @Query("SELECT * FROM links WHERE dirty > 0")
    abstract suspend fun dirtyLinks(): List<LinkWithTags>

    /** Links deleted here and not yet pushed as deleted. */
    @Query("SELECT * FROM sync_tombstones WHERE kind = '${LinkSyncSchema.KIND_LINK}'")
    abstract suspend fun tombstones(): List<SyncTombstoneEntity>

    /**
     * Marks a pushed link in sync. [dirty] is the count read before the push: if the link changed
     * again meanwhile, the count moved on and the link stays dirty for the next push.
     */
    @Query("UPDATE links SET dirty = 0 WHERE uid = :uid AND dirty = :dirty")
    abstract suspend fun markPushed(uid: String, dirty: Int)

    /** Drops a pushed tombstone, unless the link was deleted again after it was read. */
    @Query("DELETE FROM sync_tombstones WHERE kind = :kind AND uid = :uid AND deletedAtMillis = :deletedAtMillis")
    abstract suspend fun deleteTombstone(kind: String, uid: String, deletedAtMillis: Long)

    /** For turning a topic item's local link id into the link uid its document carries. */
    @Query("SELECT id, uid FROM links WHERE id IN (:ids)")
    abstract suspend fun uidsOf(ids: Collection<Long>): List<LinkIdUid>

    /** For turning a pulled item's link uid back into a local link id. */
    @Query("SELECT id, uid FROM links WHERE uid IN (:uids)")
    abstract suspend fun idsOf(uids: Collection<String>): List<LinkIdUid>

    /**
     * Applies pulled link documents in one transaction, with change tracking switched off so none
     * of it turns dirty (docs/CLOUD_SYNC_PLAN.md › Pull). Last writer wins, per [decideRemote].
     */
    @Transaction
    open suspend fun applyPulled(links: List<RemoteLink>): LinkPullResult {
        setApplying(true)
        val needsImage = mutableListOf<Long>()
        val deleted = mutableListOf<Long>()
        for (remote in links) {
            val urlKey = linkUrlKey(remote.url)
            // By uid first; by address for a row saved under another uid (an older extension build, say).
            val local = findByUid(remote.uid) ?: findByUrlKey(urlKey)
            val tombstone = if (local == null) findTombstone(LinkSyncSchema.KIND_LINK, remote.uid) else null
            val version = local?.let { LocalVersion(it.updatedAtMillis, dirty = it.dirty > 0) }
                ?: tombstone?.let { LocalVersion(it.deletedAtMillis, dirty = true) }

            when (decideRemote(version, remote.updatedAtMillis, remote.deleted)) {
                RemoteAction.Skip -> Unit
                RemoteAction.Delete -> {
                    if (local != null) {
                        deleteWithOrphanedTags(local.id)
                        deleted += local.id
                    }
                    tombstone?.let { deleteTombstone(it.kind, it.uid, it.deletedAtMillis) }
                }
                RemoteAction.Apply -> {
                    val sameImage = local != null && local.imageUrl == remote.imageUrl
                    val row = LinkEntity(
                        id = local?.id ?: 0,
                        url = remote.url,
                        title = remote.title,
                        createdAtMillis = remote.createdAtMillis,
                        imageUrl = remote.imageUrl,
                        imagePath = if (sameImage) local?.imagePath else null,
                        imageHidden = remote.imageHidden,
                        urlKey = urlKey,
                        uid = remote.uid,
                        dirty = 0,
                        updatedAtMillis = remote.updatedAtMillis,
                    )
                    val id = if (local == null) insertPulled(row) else local.id.also { updatePulled(row) }
                    replaceTags(id, remote.tags.map { it.trim() }.filter { it.isNotEmpty() })
                    if (remote.imageUrl != null && !sameImage) needsImage += id
                    tombstone?.let { deleteTombstone(it.kind, it.uid, it.deletedAtMillis) }
                }
            }
        }
        setApplying(false)
        return LinkPullResult(needsImage = needsImage, deleted = deleted)
    }

    @Query("UPDATE sync_control SET applying = :applying WHERE id = 0")
    protected abstract suspend fun setApplying(applying: Boolean)

    @Query("SELECT * FROM links WHERE uid = :uid")
    protected abstract suspend fun findByUid(uid: String): LinkEntity?

    @Query("SELECT * FROM links WHERE urlKey = :urlKey")
    protected abstract suspend fun findByUrlKey(urlKey: String): LinkEntity?

    @Query("SELECT * FROM sync_tombstones WHERE kind = :kind AND uid = :uid")
    protected abstract suspend fun findTombstone(kind: String, uid: String): SyncTombstoneEntity?

    @Insert
    protected abstract suspend fun insertPulled(link: LinkEntity): Long

    @Update
    protected abstract suspend fun updatePulled(link: LinkEntity)
}
