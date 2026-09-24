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
import java.util.UUID

@Entity(
    tableName = "topics",
    indices = [Index(value = ["name"], unique = true), Index(value = ["uid"], unique = true)],
)
data class TopicEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Unique ignoring case. */
    @ColumnInfo(collate = ColumnInfo.NOCASE) val name: String,
    val purpose: String?,
    val pinned: Boolean,
    /** Comma-separated [dev.kortex.myinfo.topics.domain.model.ItemType] names. */
    val sections: String,
    val createdAtMillis: Long,
    /**
     * When the topic or its items last changed, as the screens show and sort it. Kept by the DAO, not
     * the sync triggers, so pinning or a new summary doesn't lift a topic to the top of the list.
     */
    val updatedAtMillis: Long,
    /** The topic's document id in the cloud. */
    val uid: String = UUID.randomUUID().toString(),
    /**
     * Local changes not yet pushed: 0 when in sync. Triggers count up on every change, so a push
     * can tell whether the row changed again while it was in flight.
     */
    val dirty: Int = 1,
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
    indices = [Index(value = ["topicId", "linkId"], unique = true), Index(value = ["uid"], unique = true)],
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
    /** The item's document id in the cloud. */
    val uid: String = UUID.randomUUID().toString(),
    /** Local changes not yet pushed, counted as on [TopicEntity.dirty]. */
    val dirty: Int = 1,
    /** Last change to anything that syncs, kept by triggers. The last writer wins on it. */
    val updatedAtMillis: Long = addedAtMillis,
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

@Database(
    entities = [
        TopicEntity::class,
        TopicItemEntity::class,
        TopicSummaryEntity::class,
        SyncTombstoneEntity::class,
        SyncControlEntity::class,
    ],
    version = 5,
    exportSchema = false,
)
abstract class TopicsDatabase : RoomDatabase() {
    abstract fun topicDao(): TopicDao
    abstract fun topicSyncDao(): TopicSyncDao

    companion object {
        /** A fresh database gets its tables from Room; the sync switch row and triggers come from here. */
        val SYNC_ON_CREATE = object : Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                TopicSyncSchema.seedControl(db)
                TopicSyncSchema.createTriggers(db)
            }
        }

        /**
         * Cloud sync (docs/CLOUD_SYNC_PLAN.md): a uid and change counter on topics and items, a
         * change time on items, the tombstone and switch tables, and the triggers that keep them.
         * Existing rows get random uids and start dirty, so the first sync uploads everything.
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                for (table in listOf("topics", "topic_items")) {
                    db.execSQL("ALTER TABLE $table ADD COLUMN uid TEXT NOT NULL DEFAULT ''")
                    db.execSQL("ALTER TABLE $table ADD COLUMN dirty INTEGER NOT NULL DEFAULT 1")
                    val ids = buildList {
                        db.query("SELECT id FROM $table").use { cursor -> while (cursor.moveToNext()) add(cursor.getLong(0)) }
                    }
                    ids.forEach { id ->
                        db.execSQL("UPDATE $table SET uid = ? WHERE id = ?", arrayOf<Any>(UUID.randomUUID().toString(), id))
                    }
                    db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_${table}_uid` ON `$table` (`uid`)")
                }
                db.execSQL("ALTER TABLE topic_items ADD COLUMN updatedAtMillis INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE topic_items SET updatedAtMillis = addedAtMillis")
                TopicSyncSchema.createTables(db)
                TopicSyncSchema.seedControl(db)
                TopicSyncSchema.createTriggers(db)
            }
        }

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
