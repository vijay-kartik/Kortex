package dev.kortex.links.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TagDao {
    @Query("SELECT * FROM tags ORDER BY name")
    fun observeAll(): Flow<List<TagEntity>>

    @Query("SELECT * FROM tags")
    suspend fun getAll(): List<TagEntity>

    @Query(
        """
        SELECT tags.name AS name, COUNT(link_tags.linkId) AS linkCount
        FROM tags LEFT JOIN link_tags ON link_tags.tagId = tags.id
        GROUP BY tags.id
        ORDER BY tags.name
        """
    )
    fun observeTagLinkCounts(): Flow<List<TagLinkCount>>

    @Query("SELECT * FROM tags WHERE name IN (:names)")
    suspend fun getByNames(names: List<String>): List<TagEntity>

    /** Returns -1 when a tag with the same name (case-insensitive) already exists. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(tag: TagEntity): Long

    @Query("UPDATE tags SET embedding = :embedding, embeddingModel = :model WHERE id = :id")
    suspend fun updateEmbedding(id: Long, embedding: ByteArray, model: String)
}
