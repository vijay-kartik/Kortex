package dev.kortex.myinfo.topics.data.local

import androidx.room.ColumnInfo
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.RoomDatabase

@Entity(tableName = "topics", indices = [Index(value = ["name"], unique = true)])
data class TopicEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Unique ignoring case. */
    @ColumnInfo(collate = ColumnInfo.NOCASE) val name: String,
    val purpose: String?,
    val pinned: Boolean,
    /** Comma-separated [dev.kortex.myinfo.topics.domain.model.ItemType] names. */
    val sections: String,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
)

/**
 * One row for every item type; [type] says which columns apply. Link-backed items hold only
 * [linkId]: the link itself lives in the Links library, in another database, so there is no
 * foreign key to it.
 */
@Entity(
    tableName = "topic_items",
    foreignKeys = [
        ForeignKey(entity = TopicEntity::class, parentColumns = ["id"], childColumns = ["topicId"], onDelete = ForeignKey.CASCADE),
    ],
    // A topic holds a link at most once. SQLite lets NULLs repeat, so other types are unaffected.
    indices = [Index(value = ["topicId", "linkId"], unique = true)],
)
data class TopicItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val topicId: Long,
    /** [dev.kortex.myinfo.topics.domain.model.ItemType] name. */
    val type: String,
    val addedAtMillis: Long,
    /** Doc and bill title. */
    val title: String? = null,
    /** Note text, image caption. */
    val text: String? = null,
    /** Link, article, video. */
    val linkId: Long? = null,
    /** Doc, image, and a bill's attachment. */
    val filePath: String? = null,
    val mimeType: String? = null,
    val pageCount: Int? = null,
    /** Bill amount in the currency's minor unit. */
    val amountMinor: Long? = null,
    val currency: String? = null,
    val issuedAtMillis: Long? = null,
    val dueAtMillis: Long? = null,
    val durationSeconds: Int? = null,
    val readingMinutes: Int? = null,
    /** Article read, video watched, bill paid. */
    @ColumnInfo(defaultValue = "0") val done: Boolean = false,
)

@Database(entities = [TopicEntity::class, TopicItemEntity::class], version = 1, exportSchema = false)
abstract class TopicsDatabase : RoomDatabase() {
    abstract fun topicDao(): TopicDao
}
