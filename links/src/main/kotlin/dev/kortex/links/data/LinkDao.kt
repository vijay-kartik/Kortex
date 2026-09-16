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

    @Insert
    abstract suspend fun insert(link: LinkEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertTagRefs(refs: List<LinkTagCrossRef>)

    @Transaction
    open suspend fun insertWithTags(link: LinkEntity, tagIds: List<Long>): Long {
        val linkId = insert(link)
        insertTagRefs(tagIds.map { LinkTagCrossRef(linkId = linkId, tagId = it) })
        return linkId
    }
}
