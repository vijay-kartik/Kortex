package dev.kortex.links.data

import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * No SQLite on the JVM here, so this checks the SQL the migrations run rather than running it. Reads
 * are answered from [rows], so the Kotlin backfills can be checked too.
 */
class LinksMigrationTest {
    private class Statement(val sql: String, val args: List<Any?> = emptyList())

    private val statements = mutableListOf<Statement>()
    private var rows: List<List<Any>> = emptyList()

    private val db = Proxy.newProxyInstance(
        SupportSQLiteDatabase::class.java.classLoader,
        arrayOf(SupportSQLiteDatabase::class.java),
    ) { _, method, args ->
        when {
            method.name == "execSQL" -> {
                statements += Statement(args[0] as String, (args.getOrNull(1) as Array<*>?)?.toList().orEmpty())
                null
            }
            method.name == "query" && args.size == 1 && args[0] is String -> cursor(rows)
            else -> error("Unexpected call ${method.name}")
        }
    } as SupportSQLiteDatabase

    @Test
    fun `1 to 2 adds the thumbnail columns`() {
        LinksDatabase.MIGRATION_1_2.migrate(db)

        assertEquals(
            listOf(
                "ALTER TABLE links ADD COLUMN imageUrl TEXT",
                "ALTER TABLE links ADD COLUMN imagePath TEXT",
                "ALTER TABLE links ADD COLUMN imageHidden INTEGER NOT NULL DEFAULT 0",
            ),
            statements.map { it.sql },
        )
    }

    @Test
    fun `2 to 3 backfills each row's url key before the unique index goes on`() {
        rows = listOf(listOf(1L, "https://Example.com/a?utm_source=x"), listOf(2L, "https://example.com/b"))

        LinksDatabase.MIGRATION_2_3.migrate(db)

        val updates = statements.filter { it.sql == "UPDATE links SET urlKey = ? WHERE id = ?" }
        assertEquals(
            listOf(
                listOf(linkUrlKey("https://Example.com/a?utm_source=x"), 1L),
                listOf(linkUrlKey("https://example.com/b"), 2L),
            ),
            updates.map { it.args },
        )
        assertTrue(indexOf("index_links_urlKey") > statements.indexOf(updates.last()))
    }

    @Test
    fun `3 to 4 backfills each row's uid as a fresh link would get it`() {
        val urls = listOf("https://Example.com/a?utm_source=x", "https://example.com/b")
        rows = urls.mapIndexed { i, url -> listOf(i + 1L, linkUrlKey(url)) }

        LinksDatabase.MIGRATION_3_4.migrate(db)

        val updates = statements.filter { it.sql == "UPDATE links SET uid = ? WHERE id = ?" }
        assertEquals(
            urls.mapIndexed { i, url -> listOf(LinkEntity(url = url, title = "", createdAtMillis = 0).uid, i + 1L) },
            updates.map { it.args },
        )
        assertTrue("UPDATE links SET updatedAtMillis = createdAtMillis" in statements.map { it.sql })
        assertTrue(indexOf("index_links_uid") > statements.indexOf(updates.last()))
        assertSyncSchemaCreated()
    }

    @Test
    fun `an empty table runs no updates and still gets its indexes and triggers`() {
        LinksDatabase.MIGRATION_2_3.migrate(db)
        LinksDatabase.MIGRATION_3_4.migrate(db)

        assertTrue(statements.none { it.args.isNotEmpty() })
        assertTrue(indexOf("index_links_urlKey") >= 0)
        assertTrue(indexOf("index_links_uid") >= 0)
        assertSyncSchemaCreated()
    }

    @Test
    fun `migrations run without gaps from version 1 to the current one`() {
        assertEquals(
            (1 until LinksDatabase.VERSION).map { it to it + 1 },
            LinksDatabase.MIGRATIONS.map { it.startVersion to it.endVersion },
        )
    }

    @Test
    fun `every link column either marks the link dirty or is deliberately left out`() {
        LinkSyncSchema.createTriggers(db)

        // Local bookkeeping, derived from url, never changed, or (imagePath) a file on this device only.
        val unsynced = setOf("id", "urlKey", "uid", "dirty", "createdAtMillis", "updatedAtMillis", "imagePath")
        val columns = LinkEntity::class.java.declaredFields
            .filterNot { Modifier.isStatic(it.modifiers) }
            .map { it.name }
            .filterNot { it in unsynced }
        assertEquals(columns.toSet(), watchedColumns("sync_links_updated", "links").toSet())
    }

    @Test
    fun `a deleted link leaves a tombstone of the sync engine's kind`() {
        LinkSyncSchema.createTriggers(db)

        assertTrue("VALUES ('link', OLD.uid," in statements.single { "sync_links_deleted" in it.sql }.sql)
        assertEquals("link", LinkSyncSchema.KIND_LINK)
    }

    private fun assertSyncSchemaCreated() {
        assertTrue(indexOf("CREATE TABLE IF NOT EXISTS `sync_tombstones`") >= 0)
        assertTrue(indexOf("CREATE TABLE IF NOT EXISTS `sync_control`") >= 0)
        assertTrue(indexOf("INSERT OR IGNORE INTO sync_control (id, applying) VALUES (0, 0)") >= 0)
        listOf("sync_links_updated", "sync_link_tags_added", "sync_link_tags_removed", "sync_links_deleted").forEach {
            assertTrue(it, indexOf("CREATE TRIGGER IF NOT EXISTS $it\n") >= 0)
        }
    }

    private fun indexOf(sqlPart: String) = statements.indexOfFirst { sqlPart in it.sql }

    private fun watchedColumns(trigger: String, table: String): List<String> {
        val sql = statements.single { "CREATE TRIGGER IF NOT EXISTS $trigger\n" in it.sql }.sql
        return Regex("AFTER UPDATE OF (.+) ON $table\\b").find(sql)!!.groupValues[1].split(", ")
    }

    private fun cursor(rows: List<List<Any>>): Cursor {
        var position = -1
        return Proxy.newProxyInstance(Cursor::class.java.classLoader, arrayOf(Cursor::class.java)) { _, method, args ->
            when (method.name) {
                "moveToNext" -> ++position < rows.size
                "getLong" -> rows[position][args[0] as Int] as Long
                "getString" -> rows[position][args[0] as Int] as String
                "close" -> null
                else -> error("Unexpected call ${method.name}")
            }
        } as Cursor
    }
}
