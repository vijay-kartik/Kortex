package dev.kortex.links.data

import androidx.room.ColumnInfo
import androidx.room.Database
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Junction
import androidx.room.PrimaryKey
import androidx.room.Relation
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Entity(
    tableName = "links",
    indices = [Index(value = ["urlKey"], unique = true), Index(value = ["uid"], unique = true)],
)
data class LinkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** The address as saved, tracking parameters and all; opening the link uses this. */
    val url: String,
    val title: String,
    val createdAtMillis: Long,
    /** The page's share image, kept so a failed download can be retried later. */
    val imageUrl: String? = null,
    /** Local thumbnail of [imageUrl]; null until it has downloaded. */
    val imagePath: String? = null,
    /** The user chose the link icon over the image. The thumbnail stays on disk so it can come back. */
    @ColumnInfo(defaultValue = "0") val imageHidden: Boolean = false,
    /** [linkUrlKey] of [url]. Unique, so the same page can't be saved twice. */
    val urlKey: String = linkUrlKey(url),
    /** [linkUid] of [urlKey]: the link's document id in the cloud. */
    val uid: String = linkUid(urlKey),
    /**
     * Local changes not yet pushed: 0 when in sync. Triggers count up on every change, so a push
     * can tell whether the row changed again while it was in flight.
     */
    val dirty: Int = 1,
    /** Last change to anything that syncs, kept by triggers. The last writer wins on it. */
    val updatedAtMillis: Long = createdAtMillis,
)

@Entity(tableName = "tags", indices = [Index(value = ["name"], unique = true)])
data class TagEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(collate = ColumnInfo.NOCASE) val name: String,
    /** Cached vector of [name] (little-endian floats), valid only while [embeddingModel] matches the current embedder. */
    val embedding: ByteArray? = null,
    val embeddingModel: String? = null,
)

@Entity(
    tableName = "link_tags",
    primaryKeys = ["linkId", "tagId"],
    foreignKeys = [
        ForeignKey(entity = LinkEntity::class, parentColumns = ["id"], childColumns = ["linkId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = TagEntity::class, parentColumns = ["id"], childColumns = ["tagId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("tagId")],
)
data class LinkTagCrossRef(
    val linkId: Long,
    val tagId: Long,
)

data class LinkWithTags(
    @Embedded val link: LinkEntity,
    @Relation(
        entity = TagEntity::class,
        parentColumn = "id",
        entityColumn = "id",
        associateBy = Junction(LinkTagCrossRef::class, parentColumn = "linkId", entityColumn = "tagId"),
        projection = ["name"],
    )
    val tagNames: List<String>,
)

data class TagLinkCount(
    val name: String,
    val linkCount: Int,
)

@Database(
    entities = [
        LinkEntity::class,
        TagEntity::class,
        LinkTagCrossRef::class,
        SyncTombstoneEntity::class,
        SyncControlEntity::class,
    ],
    version = 4,
    exportSchema = false,
)
abstract class LinksDatabase : RoomDatabase() {
    abstract fun linkDao(): LinkDao
    abstract fun tagDao(): TagDao
    abstract fun linkSyncDao(): LinkSyncDao

    companion object {
        /** A fresh database gets its tables from Room; the sync switch row and triggers come from here. */
        val SYNC_ON_CREATE = object : Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                LinkSyncSchema.seedControl(db)
                LinkSyncSchema.createTriggers(db)
            }
        }

        /**
         * Cloud sync (docs/CLOUD_SYNC_PLAN.md): a uid, change counter and change time on every link,
         * the tombstone and switch tables, and the triggers that keep them. Uids are derived in
         * Kotlin, so existing rows are backfilled one by one, as in [MIGRATION_2_3]. Every link starts
         * dirty, so the first sync uploads the whole library.
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE links ADD COLUMN uid TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE links ADD COLUMN dirty INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE links ADD COLUMN updatedAtMillis INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE links SET updatedAtMillis = createdAtMillis")
                val rows = buildList {
                    db.query("SELECT id, urlKey FROM links").use { cursor ->
                        while (cursor.moveToNext()) add(cursor.getLong(0) to cursor.getString(1))
                    }
                }
                rows.forEach { (id, urlKey) ->
                    db.execSQL("UPDATE links SET uid = ? WHERE id = ?", arrayOf<Any>(linkUid(urlKey), id))
                }
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_links_uid` ON `links` (`uid`)")
                LinkSyncSchema.createTables(db)
                LinkSyncSchema.seedControl(db)
                LinkSyncSchema.createTriggers(db)
            }
        }

        /** Adds link thumbnails. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE links ADD COLUMN imageUrl TEXT")
                db.execSQL("ALTER TABLE links ADD COLUMN imagePath TEXT")
                db.execSQL("ALTER TABLE links ADD COLUMN imageHidden INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * Makes addresses unique by [linkUrlKey]. Keys are computed in Kotlin, so existing rows are
         * backfilled one by one before the unique index goes on. Assumes no duplicates exist yet:
         * if two rows share a key, creating the index fails.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE links ADD COLUMN urlKey TEXT NOT NULL DEFAULT ''")
                val rows = buildList {
                    db.query("SELECT id, url FROM links").use { cursor ->
                        while (cursor.moveToNext()) add(cursor.getLong(0) to cursor.getString(1))
                    }
                }
                rows.forEach { (id, url) ->
                    db.execSQL("UPDATE links SET urlKey = ? WHERE id = ?", arrayOf<Any>(linkUrlKey(url), id))
                }
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_links_urlKey` ON `links` (`urlKey`)")
            }
        }
    }
}
