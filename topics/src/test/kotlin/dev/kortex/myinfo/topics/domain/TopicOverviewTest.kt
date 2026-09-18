package dev.kortex.myinfo.topics.domain

import dev.kortex.myinfo.topics.domain.model.ItemType
import dev.kortex.myinfo.topics.domain.model.Money
import dev.kortex.myinfo.topics.domain.model.Progress
import dev.kortex.myinfo.topics.domain.model.SavedLink
import dev.kortex.myinfo.topics.domain.model.StoredFile
import dev.kortex.myinfo.topics.domain.model.Topic
import dev.kortex.myinfo.topics.domain.model.TopicDetail
import dev.kortex.myinfo.topics.domain.model.TopicItem
import dev.kortex.myinfo.topics.domain.model.TopicOverview
import dev.kortex.myinfo.topics.domain.model.TopicSort
import dev.kortex.myinfo.topics.domain.model.sortedFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TopicOverviewTest {
    @Test
    fun `counts only the types present, in type order`() {
        val overview = TopicOverview.of(topic(), listOf(bill(1, "AED", 100), note(2), note(3), image(4, "a.jpg")))

        assertEquals(4, overview.itemCount)
        assertEquals(listOf(ItemType.Note to 2, ItemType.Image to 1, ItemType.Bill to 1), overview.counts.toList())
    }

    @Test
    fun `bills are totalled per currency`() {
        val items = listOf(bill(1, "AED", 291_000), bill(2, "INR", 1_845_000), bill(3, "AED", 37_000))

        assertEquals(
            listOf(Money(328_000, "AED"), Money(1_845_000, "INR")),
            TopicOverview.of(topic(), items).billTotals,
        )
    }

    @Test
    fun `reading progress counts articles only`() {
        val items = listOf(article(1, read = true), article(2, read = false), article(3, read = true), note(4))

        assertEquals(Progress(done = 2, total = 3), TopicOverview.of(topic(), items).reading)
        assertNull(TopicOverview.of(topic(), listOf(note(1))).reading)
    }

    @Test
    fun `previews are image and link thumbnails, newest first`() {
        val items = listOf(
            image(1, "old.jpg", addedAt = 10),
            video(2, thumbnail = "video.jpg", addedAt = 30),
            video(3, thumbnail = null, addedAt = 40),
            image(4, "new.jpg", addedAt = 50),
            note(5, addedAt = 60),
        )

        assertEquals(listOf("new.jpg", "video.jpg", "old.jpg"), TopicOverview.of(topic(), items).previews)
    }

    @Test
    fun `hidden sections show once the topic holds that type`() {
        val detail = TopicDetail(topic(sections = setOf(ItemType.Note)), listOf(bill(1, "AED", 100)))

        assertEquals(setOf(ItemType.Note, ItemType.Bill), detail.visibleSections)
    }

    @Test
    fun `the bills card totals what's still owed and counts what's paid`() {
        val items = listOf(
            bill(1, "AED", 291_000, paid = true, dueAt = 100),
            bill(2, "AED", 37_000, paid = false, dueAt = 500),
            bill(3, "AED", 50_000, paid = false, dueAt = 300),
            bill(4, "INR", 1_845_000, paid = false, dueAt = null),
            note(5),
        )

        val bills = checkNotNull(TopicDetail(topic(), items).bills)
        assertEquals(listOf(Money(87_000, "AED"), Money(1_845_000, "INR")), bills.outstanding)
        assertEquals(Progress(done = 1, total = 4), bills.paid)
        // The paid bill's earlier date doesn't count: it isn't waiting on anyone.
        assertEquals(300L, bills.nextDueAtMillis)
    }

    @Test
    fun `a topic with every bill paid owes nothing, and one with no bills has no card`() {
        val paid = checkNotNull(TopicDetail(topic(), listOf(bill(1, "AED", 291_000, paid = true))).bills)
        assertEquals(emptyList<Money>(), paid.outstanding)
        assertEquals(Progress(done = 1, total = 1), paid.paid)
        assertNull(paid.nextDueAtMillis)

        assertNull(TopicDetail(topic(), listOf(note(1))).bills)
    }

    @Test
    fun `sorts recent with pinned first, pinned only, and by name`() {
        val topics = listOf(
            overview(1, "b", pinned = false, updatedAt = 300),
            overview(2, "a", pinned = true, updatedAt = 100),
            overview(3, "C", pinned = false, updatedAt = 200),
        )

        assertEquals(listOf(2L, 1L, 3L), topics.sortedFor(TopicSort.Recent).map { it.topic.id })
        assertEquals(listOf(2L), topics.sortedFor(TopicSort.Pinned).map { it.topic.id })
        assertEquals(listOf(2L, 1L, 3L), topics.sortedFor(TopicSort.Alphabetical).map { it.topic.id })
    }

    private fun topic(
        id: Long = 1,
        name: String = "Trip to Dubai",
        pinned: Boolean = false,
        updatedAt: Long = 0,
        sections: Set<ItemType> = ItemType.DefaultSections,
    ) = Topic(id, name, purpose = null, pinned, sections, createdAtMillis = 0, updatedAtMillis = updatedAt)

    private fun overview(id: Long, name: String, pinned: Boolean, updatedAt: Long) =
        TopicOverview.of(topic(id, name, pinned, updatedAt), emptyList())

    private fun note(id: Long, addedAt: Long = 0) = TopicItem.Note(id, topicId = 1, addedAt, text = "note $id")

    private fun image(id: Long, path: String, addedAt: Long = 0) =
        TopicItem.Image(id, topicId = 1, addedAt, StoredFile(path, "image/jpeg"), caption = null)

    private fun video(id: Long, thumbnail: String?, addedAt: Long = 0) =
        TopicItem.Video(id, topicId = 1, addedAt, link(id, thumbnail), durationSeconds = null, watched = false)

    private fun article(id: Long, read: Boolean) =
        TopicItem.Article(id, topicId = 1, addedAtMillis = 0, link(id, null), readingMinutes = null, read = read)

    private fun bill(id: Long, currency: String, minor: Long, paid: Boolean = false, dueAt: Long? = null) = TopicItem.Bill(
        id, topicId = 1, addedAtMillis = 0, title = "bill $id", amount = Money(minor, currency),
        issuedAtMillis = null, dueAtMillis = dueAt, paid = paid, file = null,
    )

    private fun link(id: Long, thumbnail: String?) = SavedLink(id, "https://example.com/$id", "Link $id", thumbnail)
}
