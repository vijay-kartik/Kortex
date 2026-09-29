package dev.kortex.myinfo.topics.ui

import dev.kortex.myinfo.topics.domain.model.SavedLink
import dev.kortex.myinfo.topics.domain.model.SeenRange
import dev.kortex.myinfo.topics.domain.model.SeenRanges
import dev.kortex.myinfo.topics.domain.model.TopicItem
import dev.kortex.myinfo.topics.domain.model.VideoProgress
import dev.kortex.myinfo.topics.ui.common.spokenDuration
import dev.kortex.myinfo.topics.ui.player.progressLine
import dev.kortex.myinfo.topics.ui.player.videoMetaLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoProgressTextTest {
    private val video = TopicItem.Video(
        id = 1,
        topicId = 7,
        addedAtMillis = NOW - 2 * HOUR,
        link = SavedLink(1, "https://youtu.be/dQw4w9WgXcQ", "Dubai in 3 days", thumbnailPath = null),
        durationSeconds = 842,
        watched = false,
    )

    private fun played(resume: Int, vararg ranges: Pair<Int, Int>, watched: Boolean = false) = video.copy(
        watched = watched,
        progress = VideoProgress(
            resume,
            ranges.fold(SeenRanges.Empty) { acc, (start, end) -> acc + SeenRange(start, end) },
            lastPlayedAtMillis = NOW - 26 * HOUR,
        ),
    )

    @Test
    fun `no length, no card`() {
        assertNull(progressLine(video.copy(durationSeconds = null), positionSeconds = 10))
    }

    @Test
    fun `just opened and just started`() {
        assertEquals("NOT STARTED", progressLine(video, positionSeconds = null)!!.label)
        val started = progressLine(video, positionSeconds = 17)!!
        assertEquals("JUST STARTED", started.label)
        assertEquals("13:45 LEFT", started.trailing)
    }

    @Test
    fun `part seen, with the legend once parts were skipped`() {
        val line = progressLine(played(320, 0 to 190, 300 to 320), positionSeconds = null)!!
        assertEquals("25% WATCHED", line.label)
        assertEquals("8:42 LEFT", line.trailing)
        assertEquals(320, line.markerSeconds)
        assertTrue(line.showsLegend)
        assertEquals("25% watched, 8 minutes 42 seconds left", line.spoken)
    }

    @Test
    fun `the live position wins over the saved one`() {
        assertEquals(400, progressLine(played(320, 0 to 320), positionSeconds = 400)!!.markerSeconds)
    }

    @Test
    fun `watched to the end`() {
        val line = progressLine(played(842, 0 to 400, 420 to 842, watched = true), positionSeconds = 842)!!
        assertEquals("✓ WATCHED · 98%", line.label)
        assertEquals("FINISHED", line.trailing)
        assertNull(line.markerSeconds)
        assertFalse(line.showsLegend)
    }

    @Test
    fun `meta line says when it was last touched`() {
        assertEquals("YOUTUBE · 14:02 · ADDED 2H AGO", videoMetaLine(video, NOW))
        assertEquals("YOUTUBE · 14:02 · LAST WATCHED YESTERDAY", videoMetaLine(played(320, 0 to 320), NOW))
        assertEquals("YOUTUBE · 14:02 · WATCHED YESTERDAY", videoMetaLine(played(842, 0 to 842, watched = true), NOW))
        assertEquals("YOUTUBE · ADDED 2H AGO", videoMetaLine(video.copy(durationSeconds = null), NOW))
    }

    @Test
    fun `durations read aloud`() {
        assertEquals("1 hour 4 minutes", spokenDuration(3_845))
        assertEquals("8 minutes 42 seconds", spokenDuration(522))
        assertEquals("1 minute", spokenDuration(60))
        assertEquals("30 seconds", spokenDuration(30))
    }

    private companion object {
        const val NOW = 1_700_000_000_000L
        const val HOUR = 3_600_000L
    }
}
