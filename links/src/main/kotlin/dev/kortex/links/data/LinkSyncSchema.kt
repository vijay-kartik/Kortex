package dev.kortex.links.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.sqlite.db.SupportSQLiteDatabase

/** A link deleted here and not yet pushed as `deleted: true`. Written by the delete trigger only. */
@Entity(tableName = "sync_tombstones", primaryKeys = ["kind", "uid"])
data class SyncTombstoneEntity(
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
internal object LinkSyncSchema {
    const val KIND_LINK = "link"

    /** Columns a link's cloud document is made of; the local thumbnail path isn't one. */
    private val SYNCED_COLUMNS = listOf("url", "title", "imageUrl", "imageHidden")

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
        // Only real changes to what syncs; setting a thumbnail path or clearing `dirty` doesn't count.
        db.execSQL(
            """
            CREATE TRIGGER IF NOT EXISTS sync_links_updated
            AFTER UPDATE OF ${SYNCED_COLUMNS.joinToString()} ON links
            WHEN $TRACKING AND (${changed(SYNCED_COLUMNS)})
            BEGIN
                UPDATE links SET dirty = dirty + 1, updatedAtMillis = $NOW_MILLIS WHERE id = NEW.id;
            END
            """.trimIndent(),
        )
        // Tags are an array on the link's document, so a tag added or removed changes the link.
        db.execSQL(
            """
            CREATE TRIGGER IF NOT EXISTS sync_link_tags_added
            AFTER INSERT ON link_tags WHEN $TRACKING
            BEGIN
                UPDATE links SET dirty = dirty + 1, updatedAtMillis = $NOW_MILLIS WHERE id = NEW.linkId;
            END
            """.trimIndent(),
        )
        // Also fires for the cascade when a link is deleted; by then the link row is gone and nothing updates.
        db.execSQL(
            """
            CREATE TRIGGER IF NOT EXISTS sync_link_tags_removed
            AFTER DELETE ON link_tags WHEN $TRACKING
            BEGIN
                UPDATE links SET dirty = dirty + 1, updatedAtMillis = $NOW_MILLIS WHERE id = OLD.linkId;
            END
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TRIGGER IF NOT EXISTS sync_links_deleted
            AFTER DELETE ON links WHEN $TRACKING
            BEGIN
                INSERT OR REPLACE INTO sync_tombstones (kind, uid, deletedAtMillis)
                VALUES ('$KIND_LINK', OLD.uid, $NOW_MILLIS);
            END
            """.trimIndent(),
        )
    }

    private fun changed(columns: List<String>) = columns.joinToString(" OR ") { "NEW.$it IS NOT OLD.$it" }

    private const val TRACKING = "(SELECT applying FROM sync_control WHERE id = 0) = 0"
    private const val NOW_MILLIS = "CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER)"
}
