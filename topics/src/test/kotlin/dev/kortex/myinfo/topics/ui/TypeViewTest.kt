package dev.kortex.myinfo.topics.ui

import dev.kortex.myinfo.topics.domain.model.ItemType
import dev.kortex.myinfo.topics.domain.model.SavedLink
import dev.kortex.myinfo.topics.domain.model.SeenRange
import dev.kortex.myinfo.topics.domain.model.SeenRanges
import dev.kortex.myinfo.topics.domain.model.Topic
import dev.kortex.myinfo.topics.domain.model.TopicDetail
import dev.kortex.myinfo.topics.domain.model.TopicItem
import dev.kortex.myinfo.topics.domain.model.VideoProgress
import dev.kortex.myinfo.topics.ui.detail.RowStatus
import dev.kortex.myinfo.topics.ui.detail.TopicDetailState
import dev.kortex.myinfo.topics.ui.detail.formatSpan
import dev.kortex.myinfo.topics.ui.detail.typeMetaLine
import dev.kortex.myinfo.topics.ui.detail.videoRowStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Videos view (Figma: Topic videos 2b–2d), and a type's view in general. */
class TypeViewTest {
    private val topic = Topic(7, "Trip to Dubai", purpose = null, pinned = false, sections = setOf(ItemType.Video), createdAtMillis = 0, updatedAtMillis = 0)

    // The five videos of 2b: 14:02 half-watched to 5:20, 8:41 and 21:55 unwatched, 5:10 and 22:14 watched.
    private val inProgress = video(1, 842, addedAgo = 2 * HOUR).copy(
        progress = VideoProgress(320, SeenRanges.Empty + SeenRange(0, 320), lastPlayedAtMillis = NOW),
    )
    private val metro = video(2, 521, addedAgo = DAY)
    private val safari = video(3, 1_315, addedAgo = 3 * DAY)
    private val souk = video(4, 310, addedAgo = 7 * DAY).copy(watched = true)
    private val burj = video(5, 1_334, addedAgo = 14 * DAY).copy(watched = true)
    private val note = TopicItem.Note(6, 7, addedAtMillis = NOW, text = "Metro closes 00:30")

    private val detail = TopicDetail(topic, listOf(inProgress, metro, safari, souk, burj, note))

    @Test
    fun `picking videos opens their view with a status chip counting the unwatched`() {
        val state = TopicDetailState(detail = detail, filter = ItemType.Video, nowMillis = NOW)

        assertEquals(ItemType.Video, state.typeView)
        assertEquals(5, state.typeItems.size)
        assertEquals("UNWATCHED", state.statusFilter?.status?.undone)
        assertEquals(3, state.statusFilter?.count)
        assertEquals(5, state.items.size)
    }

    @Test
    fun `the status chip leaves only what's still to watch, and counts what it hides`() {
        val state = TopicDetailState(detail = detail, filter = ItemType.Video, onlyUndone = true, nowMillis = NOW)

        assertTrue(state.showsOnlyUndone)
        assertEquals(listOf(inProgress, metro, safari), state.items)
        assertEquals(2, state.hiddenDoneCount)
    }

    @Test
    fun `types never done with have no status chip, and ALL has no type view`() {
        val notes = TopicDetailState(detail = detail, filter = ItemType.Note, onlyUndone = true)
        assertNull(notes.statusFilter)
        assertFalse(notes.showsOnlyUndone)
        assertEquals(listOf(note), notes.items)

        assertNull(TopicDetailState(detail = detail).typeView)
    }

    @Test
    fun `a type's view is one plain list whatever the topic's view mode`() {
        val state = TopicDetailState(
            detail = detail,
            filter = ItemType.Video,
            mode = dev.kortex.myinfo.topics.domain.model.TopicViewMode.Timeline,
            nowMillis = NOW,
        )
        assertEquals(1, state.sections.size)
    }

    @Test
    fun `header totals the videos and what's left to watch`() {
        val all = TopicDetailState(detail = detail, filter = ItemType.Video, nowMillis = NOW)
        assertEquals("5 ITEMS · 1H 12M TOTAL · 39M LEFT TO WATCH", typeMetaLine(all))

        val unwatched = all.copy(onlyUndone = true)
        assertEquals("3 UNWATCHED · 39M LEFT TO WATCH", typeMetaLine(unwatched))
    }

    @Test
    fun `header for other types counts items, and what's left where there's a done state`() {
        assertEquals("1 ITEM", typeMetaLine(TopicDetailState(detail = detail, filter = ItemType.Note)))

        val articles = TopicDetail(
            topic,
            listOf(
                TopicItem.Article(8, 7, 0, link(8), readingMinutes = null, read = false),
                TopicItem.Article(9, 7, 0, link(9), readingMinutes = null, read = true),
            ),
        )
        assertEquals("2 ITEMS · 1 UNREAD", typeMetaLine(TopicDetailState(detail = articles, filter = ItemType.Article)))
    }

    @Test
    fun `rows say where each video stands`() {
        assertEquals(RowStatus("8:42 LEFT · 2H AGO", accent = true), videoRowStatus(inProgress, NOW))
        assertEquals(RowStatus("UNWATCHED · YESTERDAY", accent = true), videoRowStatus(metro, NOW))
        assertEquals(RowStatus("✓ WATCHED · 1W AGO", accent = false), videoRowStatus(souk, NOW))
        assertEquals(RowStatus("OPENS YOUTUBE ↗ · YESTERDAY", accent = true), videoRowStatus(metro.copy(embedBlocked = true), NOW))
    }

    @Test
    fun `SELECT is selection mode with nothing picked, and acting needs something picked`() {
        val state = TopicDetailState(detail = detail, filter = ItemType.Video, selectionMode = true)

        assertTrue(state.selecting)
        assertTrue(state.selection.isEmpty())
        assertFalse(state.selectionPinned)
        assertFalse(state.canMoveSelection)
    }

    @Test
    fun `spans read at a glance`() {
        assertEquals("1H 12M", formatSpan(4_322))
        assertEquals("2H", formatSpan(7_200))
        assertEquals("39M", formatSpan(2_358))
        assertEquals("45S", formatSpan(45))
    }

    private fun video(id: Long, length: Int, addedAgo: Long) = TopicItem.Video(
        id = id,
        topicId = 7,
        addedAtMillis = NOW - addedAgo,
        link = link(id),
        durationSeconds = length,
        watched = false,
    )

    private fun link(id: Long) = SavedLink(id, "https://youtu.be/dQw4w9WgXc$id", "Video $id", thumbnailPath = null)

    private companion object {
        const val NOW = 1_700_000_000_000L
        const val HOUR = 3_600_000L
        const val DAY = 24 * HOUR
    }
}
