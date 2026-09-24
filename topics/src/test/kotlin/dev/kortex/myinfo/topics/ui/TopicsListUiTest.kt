package dev.kortex.myinfo.topics.ui

import dev.kortex.myinfo.topics.domain.model.ItemType
import dev.kortex.myinfo.topics.domain.model.Money
import dev.kortex.myinfo.topics.domain.model.Topic
import dev.kortex.myinfo.topics.domain.model.TopicOverview
import dev.kortex.myinfo.topics.domain.model.TopicSort
import dev.kortex.myinfo.topics.ui.common.countsLabel
import dev.kortex.myinfo.topics.ui.common.formatMoney
import dev.kortex.myinfo.topics.ui.common.updatedLabel
import dev.kortex.myinfo.topics.ui.list.PendingTopicDeletion
import dev.kortex.myinfo.topics.ui.list.TopicsListState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class TopicsListUiTest {
    @Test
    fun `updated label`() {
        val now = 400L * DAY
        assertEquals("UPDATED JUST NOW", updatedLabel(now - 30_000, now))
        assertEquals("UPDATED 5M AGO", updatedLabel(now - 5 * MINUTE, now))
        assertEquals("UPDATED 2H AGO", updatedLabel(now - 2 * HOUR, now))
        assertEquals("UPDATED YESTERDAY", updatedLabel(now - 30 * HOUR, now))
        assertEquals("UPDATED 3D AGO", updatedLabel(now - 3 * DAY, now))
        assertEquals("UPDATED 2W AGO", updatedLabel(now - 14 * DAY, now))
        assertEquals("UPDATED 1Y AGO", updatedLabel(now - 366 * DAY, now))
    }

    @Test
    fun `counts label puts the most common type first`() {
        val counts = mapOf(ItemType.Note to 2, ItemType.Link to 9, ItemType.Video to 1, ItemType.Doc to 2)

        assertEquals("9 links · 2 notes · 2 docs · 1 video", countsLabel(counts))
    }

    @Test
    fun `money shows decimals only when there are some`() {
        assertEquals("AED 4,280", formatMoney(Money(428_000, "AED"), Locale.US))
        assertEquals("AED 4,280.50", formatMoney(Money(428_050, "AED"), Locale.US))
        assertEquals("JPY 1,200", formatMoney(Money(1_200, "JPY"), Locale.US))
    }

    @Test
    fun `first run only once loaded with no topics`() {
        assertFalse(TopicsListState().firstRun)
        assertTrue(TopicsListState(loading = false).firstRun)
        assertFalse(TopicsListState(loading = false, topics = listOf(overview(1))).firstRun)
    }

    @Test
    fun `counts leave out the topic being deleted`() {
        val state = TopicsListState(
            loading = false,
            topics = listOf(overview(1, pinned = true, items = 4), overview(2, items = 3)),
            pendingDeletion = PendingTopicDeletion(topicId = 1, startedAtMillis = 0, deadlineMillis = 5_000),
        )

        assertEquals(1, state.topicCount)
        assertEquals(3, state.itemCount)
        assertEquals(0, state.pinnedCount)
        assertEquals(2, state.visibleTopics.size)
    }

    @Test
    fun `options close when their topic leaves the list`() {
        val state = TopicsListState(loading = false, topics = listOf(overview(1), overview(2, pinned = true)), optionsTopicId = 1)

        assertEquals(1L, state.openOptionsTopicId)
        assertNull(state.copy(sort = TopicSort.Pinned).openOptionsTopicId)
        assertNull(state.copy(pendingDeletion = PendingTopicDeletion(1, 0, 5_000)).openOptionsTopicId)
    }

    private fun overview(id: Long, pinned: Boolean = false, items: Int = 0) = TopicOverview(
        topic = Topic(id, "Topic $id", purpose = null, pinned, emptySet(), createdAtMillis = 0, updatedAtMillis = 0),
        itemCount = items,
        counts = if (items == 0) emptyMap() else mapOf(ItemType.Note to items),
        previews = emptyList(),
        billTotals = emptyList(),
        reading = null,
    )

    private companion object {
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
        const val DAY = 24 * HOUR
    }
}
