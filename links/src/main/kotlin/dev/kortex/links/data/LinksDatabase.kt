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

@Entity(tableName = "links", indices = [Index(value = ["urlKey"], unique = true)])
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

@Database(entities = [LinkEntity::class, TagEntity::class, LinkTagCrossRef::class], version = 3, exportSchema = false)
abstract class LinksDatabase : RoomDatabase() {
    abstract fun linkDao(): LinkDao
    abstract fun tagDao(): TagDao

    companion object {
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
