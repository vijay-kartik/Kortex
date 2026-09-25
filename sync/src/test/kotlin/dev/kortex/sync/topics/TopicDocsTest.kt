package dev.kortex.sync.topics

import dev.kortex.myinfo.topics.data.local.DirtyTopic
import dev.kortex.myinfo.topics.data.local.TopicEntity
import dev.kortex.myinfo.topics.data.local.TopicItemEntity
import dev.kortex.myinfo.topics.data.local.TopicSummaryEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TopicDocsTest {

    private val topic = TopicEntity(
        id = 3,
        name = "Dubai move",
        purpose = "Visa and flat",
        pinned = true,
        sections = "Link,Doc,Bill",
        createdAtMillis = 1_000,
        updatedAtMillis = 2_000,
    )

    @Test
    fun `a pushed topic reads back as the same topic, summary included`() {
        val summary = TopicSummaryEntity(topicId = 3, text = "Two docs left", generatedAtMillis = 1_500, fingerprint = "abc")
        val remote = remoteTopic(topic.uid, topicDoc(DirtyTopic(topic, summary), serverTime = "ts"))!!

        assertEquals("Dubai move", remote.name)
        assertEquals("Visa and flat", remote.purpose)
        assertTrue(remote.pinned)
        assertEquals(listOf("Link", "Doc", "Bill"), remote.sections)
        assertEquals(1_000L, remote.createdAtMillis)
        assertEquals(2_000L, remote.updatedAtMillis)
        assertEquals("Two docs left", remote.summary!!.text)
        assertEquals(1_500L, remote.summary!!.generatedAtMillis)
        assertEquals("abc", remote.summary!!.fingerprint)
        assertFalse(remote.deleted)
    }

    @Test
    fun `a topic without a summary writes one as null, so a merge clears it`() {
        val doc = topicDoc(DirtyTopic(topic.copy(sections = ""), summary = null), serverTime = "ts")
        assertTrue(doc.containsKey(TopicFields.SUMMARY))
        assertNull(doc[TopicFields.SUMMARY])
        assertEquals(emptyList<String>(), doc[TopicFields.SECTIONS])
    }

    @Test
    fun `a pushed item reads back with its topic, link and file`() {
        val item = TopicItemEntity(
            topicId = 3, type = "Bill", addedAtMillis = 5_000, title = "Rent",
            filePath = "/data/files/topic-files/1-rent.pdf", mimeType = "application/pdf", pageCount = 2,
            amountMinor = 450_000, currency = "AED", dueAtMillis = 9_000, done = true, updatedAtMillis = 6_000,
        )
        val remote = remoteTopicItem(item.uid, itemDoc(item, topicUid = "topic-1", linkUid = "link-1", serverTime = "ts"))!!

        assertEquals("topic-1", remote.topicUid)
        assertEquals("link-1", remote.linkUid)
        assertEquals("Bill", remote.type)
        assertEquals("Rent", remote.title)
        assertEquals("/data/files/topic-files/1-rent.pdf", remote.filePath)
        assertEquals("application/pdf", remote.mimeType)
        assertEquals(2, remote.pageCount)
        assertEquals(450_000L, remote.amountMinor)
        assertEquals("AED", remote.currency)
        assertEquals(9_000L, remote.dueAtMillis)
        assertTrue(remote.done)
        assertEquals(5_000L, remote.addedAtMillis)
        assertEquals(6_000L, remote.updatedAtMillis)
    }

    @Test
    fun `a delete reads as a delete without topic, type or name`() {
        val topicDelete = remoteTopic("t", deletedDoc(deletedAtMillis = 7_000, serverTime = "ts"))!!
        assertTrue(topicDelete.deleted)
        assertEquals(7_000L, topicDelete.updatedAtMillis)

        val itemDelete = remoteTopicItem("i", deletedDoc(deletedAtMillis = 7_000, serverTime = "ts"))!!
        assertTrue(itemDelete.deleted)
    }

    @Test
    fun `malformed documents are skipped`() {
        assertNull(remoteTopic("t", mapOf(TopicFields.UPDATED_AT to 1L)))
        assertNull(remoteTopic("t", mapOf(TopicFields.NAME to "No time")))
        assertNull(remoteTopicItem("i", mapOf(ItemFields.UPDATED_AT to 1L, ItemFields.TYPE to "Note")))
    }

    @Test
    fun `numbers written from JavaScript arrive as doubles and still read`() {
        val remote = remoteTopicItem(
            "i",
            mapOf(
                ItemFields.TOPIC_UID to "t", ItemFields.TYPE to "Note", ItemFields.ADDED_AT to 10.0,
                ItemFields.UPDATED_AT to 20.0, ItemFields.TEXT to "Call the agent",
            ),
        )!!
        assertEquals(10L, remote.addedAtMillis)
        assertEquals(20L, remote.updatedAtMillis)
        assertNull(remote.filePath)
    }
}
