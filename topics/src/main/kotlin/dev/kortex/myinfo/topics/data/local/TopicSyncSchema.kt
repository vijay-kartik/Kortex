package dev.kortex.myinfo.topics.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.sqlite.db.SupportSQLiteDatabase

/** A topic or item deleted here and not yet pushed as `deleted: true`. Written by the delete triggers only. */
@Entity(tableName = "sync_tombstones", primaryKeys = ["kind", "uid"])
data class SyncTombstoneEntity(
    /** [TopicSyncSchema.KIND_TOPIC] or [TopicSyncSchema.KIND_ITEM]. */
    val kind: String,
    val uid: String,
    val deletedAtMillis: Long,
)

/**
 * One row, id 0. The sync engine sets [applying] inside the transaction where it applies pulled
 * changes, which switches every trigger off, so those rows don't turn dirty and bounce back.
 */
@Entity(tableName = "sync_control")
data class SyncControlEntity(
    @PrimaryKey val id: Int = 0,
    val applying: Boolean = false,
)

/** SQL for local change tracking (docs/CLOUD_SYNC_PLAN.md › Change tracking). */
internal object TopicSyncSchema {
    const val KIND_TOPIC = "topic"
    const val KIND_ITEM = "topicItem"

    /** Columns a topic's cloud document is made of. `updatedAtMillis` is the DAO's, see [TopicEntity.updatedAtMillis]. */
    private val TOPIC_COLUMNS = listOf("name", "purpose", "pinned", "sections")

    /** Columns an item's cloud document is made of. The file path goes up as-is; the file itself never does. */
    private val ITEM_COLUMNS = listOf(
        "topicId", "type", "title", "text", "linkId", "filePath", "mimeType", "pageCount", "amountMinor", "currency",
        "issuedAtMillis", "dueAtMillis", "durationSeconds", "readingMinutes", "done", "pinned",
        "messageId", "threadId", "rfc822MessageId", "fromAddress", "accountEmail", "sentAtMillis",
    )

    /** Must match [SyncTombstoneEntity] and [SyncControlEntity], as Room creates them on a fresh install. */
    fun createTables(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `sync_tombstones` (" +
                "`kind` TEXT NOT NULL, `uid` TEXT NOT NULL, `deletedAtMillis` INTEGER NOT NULL, " +
                "PRIMARY KEY(`kind`, `uid`))",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `sync_control` (" +
                "`id` INTEGER NOT NULL, `applying` INTEGER NOT NULL, PRIMARY KEY(`id`))",
        )
    }

    fun seedControl(db: SupportSQLiteDatabase) {
        db.execSQL("INSERT OR IGNORE INTO sync_control (id, applying) VALUES (0, 0)")
    }

    fun createTriggers(db: SupportSQLiteDatabase) {
        // Dirty only: the topic's own timestamp stays the DAO's, as the list sorts and labels by it.
        db.execSQL(
            """
            CREATE TRIGGER IF NOT EXISTS sync_topics_updated
            AFTER UPDATE OF ${TOPIC_COLUMNS.joinToString()} ON topics
            WHEN $TRACKING AND (${changed(TOPIC_COLUMNS)})
            BEGIN
                UPDATE topics SET dirty = dirty + 1 WHERE id = NEW.id;
            END
            """.trimIndent(),
        )
        db.execSQL(deleteTrigger("sync_topics_deleted", "topics", KIND_TOPIC))
        db.execSQL(
            """
            CREATE TRIGGER IF NOT EXISTS sync_topic_items_updated
            AFTER UPDATE OF ${ITEM_COLUMNS.joinToString()} ON topic_items
            WHEN $TRACKING AND (${changed(ITEM_COLUMNS)})
            BEGIN
                UPDATE topic_items SET dirty = dirty + 1, updatedAtMillis = $NOW_MILLIS WHERE id = NEW.id;
            END
            """.trimIndent(),
        )
        // Also fires for items a topic's delete cascades to, so each of them is pushed as deleted too.
        db.execSQL(deleteTrigger("sync_topic_items_deleted", "topic_items", KIND_ITEM))
        // The summary rides on its topic's document. Saving one replaces the row, which fires the insert.
        for (event in listOf("INSERT", "UPDATE")) {
            db.execSQL(
                """
                CREATE TRIGGER IF NOT EXISTS sync_topic_summaries_${event.lowercase()}
                AFTER $event ON topic_summaries WHEN $TRACKING
                BEGIN
                    UPDATE topics SET dirty = dirty + 1 WHERE id = NEW.topicId;
                END
                """.trimIndent(),
            )
        }
    }

    private fun deleteTrigger(name: String, table: String, kind: String) =
        """
        CREATE TRIGGER IF NOT EXISTS $name
        AFTER DELETE ON $table WHEN $TRACKING
        BEGIN
            INSERT OR REPLACE INTO sync_tombstones (kind, uid, deletedAtMillis) VALUES ('$kind', OLD.uid, $NOW_MILLIS);
        END
        """.trimIndent()

    private fun changed(columns: List<String>) = columns.joinToString(" OR ") { "NEW.$it IS NOT OLD.$it" }

    private const val TRACKING = "(SELECT applying FROM sync_control WHERE id = 0) = 0"
    private const val NOW_MILLIS = "CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER)"
}
