package dev.kortex.myinfo.topics.data.local

import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * No SQLite on the JVM here, so this checks the SQL the migrations run rather than running it. Reads
 * are answered from [rows] by query, so the Kotlin backfills can be checked too.
 */
class TopicsMigrationTest {
    private class Statement(val sql: String, val args: List<Any?> = emptyList())

    private val statements = mutableListOf<Statement>()
    private var rows: Map<String, List<List<Any>>> = emptyMap()

    private val db = Proxy.newProxyInstance(
        SupportSQLiteDatabase::class.java.classLoader,
        arrayOf(SupportSQLiteDatabase::class.java),
    ) { _, method, args ->
        when {
            method.name == "execSQL" -> {
                statements += Statement(args[0] as String, (args.getOrNull(1) as Array<*>?)?.toList().orEmpty())
                null
            }
            method.name == "query" && args.size == 1 && args[0] is String -> cursor(rows[args[0]].orEmpty())
            else -> error("Unexpected call ${method.name}")
        }
    } as SupportSQLiteDatabase

    @Test
    fun `1 to 2 adds item pinning`() {
        TopicsDatabase.MIGRATION_1_2.migrate(db)

        assertEquals(listOf("ALTER TABLE topic_items ADD COLUMN pinned INTEGER NOT NULL DEFAULT 0"), statements.map { it.sql })
    }

    @Test
    fun `2 to 3 creates the summaries table as Room would`() {
        TopicsDatabase.MIGRATION_2_3.migrate(db)

        val sql = statements.single().sql
        assertTrue(sql.startsWith("CREATE TABLE IF NOT EXISTS `topic_summaries` ("))
        assertEquals(columnsOf(TopicSummaryEntity::class.java).toSet(), Regex("`(\\w+)` (?:INTEGER|TEXT)").findAll(sql).map { it.groupValues[1] }.toSet())
        assertTrue("PRIMARY KEY(`topicId`)" in sql)
        assertTrue("FOREIGN KEY(`topicId`) REFERENCES `topics`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE" in sql)
    }

    @Test
    fun `3 to 4 adds the email columns`() {
        TopicsDatabase.MIGRATION_3_4.migrate(db)

        assertEquals(
            listOf("messageId", "threadId", "rfc822MessageId", "fromAddress", "accountEmail").map { "ALTER TABLE topic_items ADD COLUMN $it TEXT" } +
                "ALTER TABLE topic_items ADD COLUMN sentAtMillis INTEGER",
            statements.map { it.sql },
        )
    }

    @Test
    fun `4 to 5 gives every topic and item its own uid before the unique indexes go on`() {
        rows = mapOf(
            "SELECT id FROM topics" to listOf(listOf(1L), listOf(2L)),
            "SELECT id FROM topic_items" to listOf(listOf(10L), listOf(11L), listOf(12L)),
        )

        TopicsDatabase.MIGRATION_4_5.migrate(db)

        val uids = mutableListOf<String>()
        for ((table, ids) in listOf("topics" to listOf(1L, 2L), "topic_items" to listOf(10L, 11L, 12L))) {
            assertTrue(indexOf("ALTER TABLE $table ADD COLUMN uid TEXT NOT NULL DEFAULT ''") >= 0)
            assertTrue(indexOf("ALTER TABLE $table ADD COLUMN dirty INTEGER NOT NULL DEFAULT 1") >= 0)
            val updates = statements.filter { it.sql == "UPDATE $table SET uid = ? WHERE id = ?" }
            assertEquals(ids, updates.map { it.args[1] })
            updates.forEach { uids += UUID.fromString(it.args[0] as String).toString() }
            assertTrue(indexOf("CREATE UNIQUE INDEX IF NOT EXISTS `index_${table}_uid` ON `$table` (`uid`)") > statements.indexOf(updates.last()))
        }
        assertEquals(5, uids.toSet().size)
        assertTrue(indexOf("UPDATE topic_items SET updatedAtMillis = addedAtMillis") > indexOf("ADD COLUMN updatedAtMillis"))
        assertSyncSchemaCreated()
    }

    @Test
    fun `4 to 5 on empty tables runs no updates and still gets its indexes and triggers`() {
        TopicsDatabase.MIGRATION_4_5.migrate(db)

        assertTrue(statements.none { it.args.isNotEmpty() })
        assertTrue(indexOf("`index_topics_uid`") >= 0)
        assertTrue(indexOf("`index_topic_items_uid`") >= 0)
        assertSyncSchemaCreated()
    }

    @Test
    fun `5 to 6 adds the video columns and makes the item trigger watch them`() {
        TopicsDatabase.MIGRATION_5_6.migrate(db)

        assertEquals(
            listOf(
                "ALTER TABLE topic_items ADD COLUMN resumeSeconds INTEGER",
                "ALTER TABLE topic_items ADD COLUMN seenRanges TEXT",
                "ALTER TABLE topic_items ADD COLUMN lastPlayedAtMillis INTEGER",
                "ALTER TABLE topic_items ADD COLUMN embedBlocked INTEGER NOT NULL DEFAULT 0",
                "DROP TRIGGER IF EXISTS sync_topic_items_updated",
            ),
            statements.dropLast(1).map { it.sql },
        )
        assertTrue(statements.last().sql.startsWith("CREATE TRIGGER IF NOT EXISTS sync_topic_items_updated\n"))
        assertTrue(watchedColumns("sync_topic_items_updated", "topic_items").containsAll(listOf("resumeSeconds", "seenRanges", "lastPlayedAtMillis", "embedBlocked")))
    }

    @Test
    fun `migrations run without gaps from version 1 to the current one`() {
        assertEquals(
            (1 until TopicsDatabase.VERSION).map { it to it + 1 },
            TopicsDatabase.MIGRATIONS.map { it.startVersion to it.endVersion },
        )
    }

    @Test
    fun `every item column either marks the item dirty or is deliberately left out`() {
        TopicSyncSchema.createTriggers(db)

        // Local bookkeeping, or (addedAtMillis) never changed.
        val unsynced = setOf("id", "uid", "dirty", "updatedAtMillis", "addedAtMillis")
        assertEquals(
            columnsOf(TopicItemEntity::class.java).filterNot { it in unsynced }.toSet(),
            watchedColumns("sync_topic_items_updated", "topic_items").toSet(),
        )
    }

    @Test
    fun `every topic column either marks the topic dirty or is deliberately left out`() {
        TopicSyncSchema.createTriggers(db)

        // Local bookkeeping, never changed, or (updatedAtMillis) the DAO's, see TopicEntity.updatedAtMillis.
        val unsynced = setOf("id", "uid", "dirty", "createdAtMillis", "updatedAtMillis")
        assertEquals(
            columnsOf(TopicEntity::class.java).filterNot { it in unsynced }.toSet(),
            watchedColumns("sync_topics_updated", "topics").toSet(),
        )
    }

    @Test
    fun `deleted topics and items leave tombstones of the sync engine's kinds`() {
        TopicSyncSchema.createTriggers(db)

        assertTrue("VALUES ('topic', OLD.uid," in statements.single { "sync_topics_deleted" in it.sql }.sql)
        assertTrue("VALUES ('topicItem', OLD.uid," in statements.single { "sync_topic_items_deleted" in it.sql }.sql)
        assertEquals("topic", SyncTombstoneEntity.KIND_TOPIC)
        assertEquals("topicItem", SyncTombstoneEntity.KIND_ITEM)
    }

    private fun assertSyncSchemaCreated() {
        assertTrue(indexOf("CREATE TABLE IF NOT EXISTS `sync_tombstones`") >= 0)
        assertTrue(indexOf("CREATE TABLE IF NOT EXISTS `sync_control`") >= 0)
        assertTrue(indexOf("INSERT OR IGNORE INTO sync_control (id, applying) VALUES (0, 0)") >= 0)
        listOf(
            "sync_topics_updated", "sync_topics_deleted", "sync_topic_items_updated", "sync_topic_items_deleted",
            "sync_topic_summaries_insert", "sync_topic_summaries_update",
        ).forEach { assertTrue(it, indexOf("CREATE TRIGGER IF NOT EXISTS $it\n") >= 0) }
    }

    private fun indexOf(sqlPart: String) = statements.indexOfFirst { sqlPart in it.sql }

    private fun columnsOf(entity: Class<*>) =
        entity.declaredFields.filterNot { Modifier.isStatic(it.modifiers) }.map { it.name }

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
