package dev.kortex.myinfo.topics.domain

import dev.kortex.myinfo.topics.domain.model.FeedGroup
import dev.kortex.myinfo.topics.domain.model.ItemType
import dev.kortex.myinfo.topics.domain.model.SavedLink
import dev.kortex.myinfo.topics.domain.model.TimePeriod
import dev.kortex.myinfo.topics.domain.model.TopicFeed
import dev.kortex.myinfo.topics.domain.model.TopicItem
import dev.kortex.myinfo.topics.domain.model.TopicViewMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class TopicFeedTest {
    // Midday, so "yesterday" and "today" don't straddle a boundary as the test runs.
    private val now = midday(daysAgo = 0)

    @Test
    fun `feed is one run of cards under no heading`() {
        val items = listOf(note(1, daysAgo = 0), note(2, daysAgo = 3), note(3, daysAgo = 90))

        val sections = TopicFeed.sections(items, TopicViewMode.Feed, now)

        assertEquals(1, sections.size)
        assertEquals(FeedGroup.Everything, sections.single().group)
        assertEquals(items, sections.single().items)
    }

    @Test
    fun `timeline cuts the feed by calendar day, leaving empty stretches out`() {
        val items = listOf(
            note(1, daysAgo = 0),
            note(2, daysAgo = 1),
            note(3, daysAgo = 3),
            note(4, daysAgo = 200),
        )

        val sections = TopicFeed.sections(items, TopicViewMode.Timeline, now)

        assertEquals(
            listOf(
                FeedGroup.Period(TimePeriod.Today),
                FeedGroup.Period(TimePeriod.Yesterday),
                FeedGroup.Period(TimePeriod.ThisWeek),
                // Nothing fell in this month, so that heading never appears.
                FeedGroup.Period(TimePeriod.Earlier),
            ),
            sections.map { it.group },
        )
        assertEquals(listOf(1L), sections.first().items.map { it.id })
    }

    @Test
    fun `by type groups busiest first, newest first within each`() {
        val items = listOf(
            link(1, daysAgo = 0),
            note(2, daysAgo = 1),
            link(3, daysAgo = 2),
            note(4, daysAgo = 3),
            note(5, daysAgo = 4),
        )

        val sections = TopicFeed.sections(items, TopicViewMode.ByType, now)

        assertEquals(listOf(FeedGroup.Type(ItemType.Note), FeedGroup.Type(ItemType.Link)), sections.map { it.group })
        assertEquals(listOf(2L, 4L, 5L), sections.first().items.map { it.id })
        assertEquals(listOf(1L, 3L), sections.last().items.map { it.id })
    }

    @Test
    fun `pinned items lead in every mode, and appear only there`() {
        val items = listOf(note(1, daysAgo = 0), note(2, daysAgo = 40, pinned = true), link(3, daysAgo = 1))

        TopicViewMode.entries.forEach { mode ->
            val sections = TopicFeed.sections(items, mode, now)
            assertEquals("$mode leads with pinned", FeedGroup.Pinned, sections.first().group)
            assertEquals("$mode pins item 2", listOf(2L), sections.first().items.map { it.id })
            assertTrue("$mode doesn't repeat it", sections.drop(1).none { section -> section.items.any { it.id == 2L } })
        }
    }

    @Test
    fun `nothing in the topic means no sections at all`() {
        assertEquals(emptyList<Any>(), TopicFeed.sections(emptyList(), TopicViewMode.Timeline, now))
    }

    @Test
    fun `periods go by the calendar, not by hours elapsed`() {
        // Just after midnight: something saved four hours ago was still yesterday.
        val justAfterMidnight = midday(daysAgo = 0) - 11 * HOUR + 10 * MINUTE
        val lateLastNight = justAfterMidnight - 4 * HOUR

        assertEquals(TimePeriod.Yesterday, TopicFeed.periodOf(lateLastNight, justAfterMidnight))
        assertEquals(TimePeriod.Today, TopicFeed.periodOf(justAfterMidnight - MINUTE, justAfterMidnight))
        // A date in the future reads as today rather than falling off the end.
        assertEquals(TimePeriod.Today, TopicFeed.periodOf(justAfterMidnight + 5 * HOUR, justAfterMidnight))
    }

    private fun midday(daysAgo: Int): Long = Calendar.getInstance().apply {
        add(Calendar.DAY_OF_YEAR, -daysAgo)
        set(Calendar.HOUR_OF_DAY, 12)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun note(id: Long, daysAgo: Int, pinned: Boolean = false) =
        TopicItem.Note(id, topicId = 1, addedAtMillis = midday(daysAgo), text = "note $id", pinned = pinned)

    private fun link(id: Long, daysAgo: Int, pinned: Boolean = false) = TopicItem.Link(
        id, topicId = 1, addedAtMillis = midday(daysAgo),
        link = SavedLink(id, "https://example.com/$id", "Link $id", thumbnailPath = null), pinned = pinned,
    )

    private companion object {
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
    }
}
