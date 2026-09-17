package dev.kortex.links.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
abstract class LinkDao {
    @Transaction
    @Query("SELECT * FROM links ORDER BY createdAtMillis DESC")
    abstract fun observeLinksWithTags(): Flow<List<LinkWithTags>>

    /** The saved link with this [linkUrlKey], if any; re-emits as links are saved or removed. */
    @Query("SELECT * FROM links WHERE urlKey = :urlKey LIMIT 1")
    abstract fun observeByUrlKey(urlKey: String): Flow<LinkEntity?>

    /** Throws [android.database.sqlite.SQLiteConstraintException] if the address is already saved. */
    @Insert
    abstract suspend fun insert(link: LinkEntity): Long

    /** @return rows updated: 0 when the link has been deleted meanwhile. */
    @Query("UPDATE links SET imageUrl = :imageUrl, imagePath = :imagePath WHERE id = :id")
    abstract suspend fun updateImage(id: Long, imageUrl: String?, imagePath: String?): Int

    /** Removes the link; its tag assignments go with it (foreign-key cascade). Its image files don't. */
    @Query("DELETE FROM links WHERE id = :id")
    abstract suspend fun delete(id: Long)

    @Query("SELECT id FROM links")
    abstract suspend fun getAllIds(): List<Long>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertTagRefs(refs: List<LinkTagCrossRef>)

    @Transaction
    open suspend fun insertWithTags(link: LinkEntity, tagIds: List<Long>): Long {
        val linkId = insert(link)
        insertTagRefs(tagIds.map { LinkTagCrossRef(linkId = linkId, tagId = it) })
        return linkId
    }
}
