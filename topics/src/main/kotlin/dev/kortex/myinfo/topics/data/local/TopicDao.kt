package dev.kortex.myinfo.topics.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
abstract class TopicDao {
    @Query("SELECT * FROM topics")
    abstract fun observeTopics(): Flow<List<TopicEntity>>

    @Query("SELECT * FROM topics WHERE id = :id")
    abstract fun observeTopic(id: Long): Flow<TopicEntity?>

    @Query("SELECT * FROM topic_items ORDER BY addedAtMillis DESC, id DESC")
    abstract fun observeItems(): Flow<List<TopicItemEntity>>

    @Query("SELECT * FROM topic_items WHERE topicId = :topicId ORDER BY addedAtMillis DESC, id DESC")
    abstract fun observeItems(topicId: Long): Flow<List<TopicItemEntity>>

    /** Throws [android.database.sqlite.SQLiteConstraintException] if the name is taken. */
    @Insert
    abstract suspend fun insertTopic(topic: TopicEntity): Long

    /** Throws [android.database.sqlite.SQLiteConstraintException] if the name is taken. */
    @Query(
        "UPDATE topics SET name = :name, purpose = :purpose, sections = :sections, pinned = :pinned, " +
            "updatedAtMillis = :nowMillis WHERE id = :id",
    )
    abstract suspend fun updateTopic(id: Long, name: String, purpose: String?, sections: String, pinned: Boolean, nowMillis: Long)

    @Query("UPDATE topics SET pinned = :pinned WHERE id = :id")
    abstract suspend fun setPinned(id: Long, pinned: Boolean)

    /** Its items go with it (foreign-key cascade). */
    @Query("DELETE FROM topics WHERE id = :id")
    abstract suspend fun deleteTopic(id: Long)

    @Query("UPDATE topics SET updatedAtMillis = :nowMillis WHERE id IN (:ids)")
    abstract suspend fun touch(ids: Collection<Long>, nowMillis: Long)

    /** -1 when the topic already holds this link. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertItem(item: TopicItemEntity): Long

    @Query("SELECT * FROM topic_items WHERE id IN (:ids)")
    abstract suspend fun getItems(ids: Collection<Long>): List<TopicItemEntity>

    /** Only the types that have something to be done with; the rest keep `done` false. */
    @Query("UPDATE topic_items SET done = :done WHERE id = :id AND type IN ('Article', 'Video', 'Bill')")
    abstract suspend fun setDone(id: Long, done: Boolean): Int

    @Query("SELECT filePath FROM topic_items WHERE id IN (:ids) AND filePath IS NOT NULL")
    abstract suspend fun filePathsOf(ids: Collection<Long>): List<String>

    @Query("SELECT filePath FROM topic_items WHERE topicId = :topicId AND filePath IS NOT NULL")
    abstract suspend fun filePathsOfTopic(topicId: Long): List<String>

    /** Every file any item still holds; the file store sweeps whatever isn't here. */
    @Query("SELECT filePath FROM topic_items WHERE filePath IS NOT NULL")
    abstract suspend fun allFilePaths(): List<String>

    @Query("SELECT EXISTS(SELECT 1 FROM topic_items WHERE topicId = :topicId AND linkId = :linkId)")
    abstract suspend fun holdsLink(topicId: Long, linkId: Long): Boolean

    @Query("UPDATE topic_items SET topicId = :topicId WHERE id = :id")
    abstract suspend fun setTopic(id: Long, topicId: Long)

    @Query("DELETE FROM topic_items WHERE id IN (:ids)")
    abstract suspend fun deleteItemRows(ids: Collection<Long>)

    /** Inserts the item and marks its topic changed. @return the item id, or -1 when the topic already holds this link. */
    @Transaction
    open suspend fun addItem(item: TopicItemEntity, nowMillis: Long): Long {
        val id = insertItem(item)
        if (id != -1L) touch(listOf(item.topicId), nowMillis)
        return id
    }

    /** Moves items to [toTopicId]; an item whose link the target already holds is deleted instead. */
    @Transaction
    open suspend fun moveItems(ids: Collection<Long>, toTopicId: Long, nowMillis: Long) {
        val items = getItems(ids).filter { it.topicId != toTopicId }
        if (items.isEmpty()) return
        items.forEach { item ->
            if (item.linkId != null && holdsLink(toTopicId, item.linkId)) {
                deleteItemRows(listOf(item.id))
            } else {
                setTopic(item.id, toTopicId)
            }
        }
        touch(items.map { it.topicId }.toSet() + toTopicId, nowMillis)
    }

    @Transaction
    open suspend fun deleteItems(ids: Collection<Long>, nowMillis: Long) {
        val topicIds = getItems(ids).map { it.topicId }.toSet()
        if (topicIds.isEmpty()) return
        deleteItemRows(ids)
        touch(topicIds, nowMillis)
    }

    /** Marks the item done and its topic changed; a type with nothing to be done leaves both. */
    @Transaction
    open suspend fun setDone(id: Long, done: Boolean, nowMillis: Long) {
        if (setDone(id, done) == 0) return
        getItems(listOf(id)).firstOrNull()?.let { touch(listOf(it.topicId), nowMillis) }
    }
}
