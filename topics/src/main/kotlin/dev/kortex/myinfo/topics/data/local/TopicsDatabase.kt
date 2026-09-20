package dev.kortex.myinfo.topics.data.local

import androidx.room.ColumnInfo
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

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
    /** Kept at the top of the topic's feed, in every view mode. */
    @ColumnInfo(defaultValue = "0") val pinned: Boolean = false,
    /** Email: the provider's message id, its thread, and the `Message-ID` header. */
    val messageId: String? = null,
    val threadId: String? = null,
    val rfc822MessageId: String? = null,
    /** Email: the sender line, the mailbox it was read from, and when it was sent. */
    val fromAddress: String? = null,
    val accountEmail: String? = null,
    val sentAtMillis: Long? = null,
)

/**
 * The agent's summary of a topic (Figma: Topics 1b), one per topic. It goes when the topic does;
 * when the topic changes it stays, and [fingerprint] tells the screen it is out of date.
 */
@Entity(
    tableName = "topic_summaries",
    foreignKeys = [
        ForeignKey(entity = TopicEntity::class, parentColumns = ["id"], childColumns = ["topicId"], onDelete = ForeignKey.CASCADE),
    ],
)
data class TopicSummaryEntity(
    @PrimaryKey val topicId: Long,
    val text: String,
    val generatedAtMillis: Long,
    /** [dev.kortex.myinfo.topics.domain.model.SummaryDigest.fingerprint] it was written from. */
    val fingerprint: String,
)

@Database(entities = [TopicEntity::class, TopicItemEntity::class, TopicSummaryEntity::class], version = 4, exportSchema = false)
abstract class TopicsDatabase : RoomDatabase() {
    abstract fun topicDao(): TopicDao

    companion object {
        /** Item pinning (Figma: Topics 1e). Everything already saved starts unpinned. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE topic_items ADD COLUMN pinned INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** Agent summaries (Figma: Topics 1b). No topic has one yet. Must match [TopicSummaryEntity]. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `topic_summaries` (" +
                        "`topicId` INTEGER NOT NULL, " +
                        "`text` TEXT NOT NULL, " +
                        "`generatedAtMillis` INTEGER NOT NULL, " +
                        "`fingerprint` TEXT NOT NULL, " +
                        "PRIMARY KEY(`topicId`), " +
                        "FOREIGN KEY(`topicId`) REFERENCES `topics`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)",
                )
            }
        }

        /** Emails kept in a topic. Every column is nullable: no item saved so far is one. */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                listOf("messageId", "threadId", "rfc822MessageId", "fromAddress", "accountEmail").forEach { column ->
                    db.execSQL("ALTER TABLE topic_items ADD COLUMN $column TEXT")
                }
                db.execSQL("ALTER TABLE topic_items ADD COLUMN sentAtMillis INTEGER")
            }
        }
    }
}
