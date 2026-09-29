package dev.kortex.myinfo.topics.domain

import dev.kortex.myinfo.topics.domain.model.PlaybackReport
import dev.kortex.myinfo.topics.domain.model.SeenRange
import dev.kortex.myinfo.topics.domain.model.SeenRanges
import dev.kortex.myinfo.topics.domain.model.VideoProgress
import dev.kortex.myinfo.topics.domain.model.VideoRecord
import dev.kortex.myinfo.topics.domain.model.startSeconds
import dev.kortex.myinfo.topics.domain.model.withPlayback
import dev.kortex.myinfo.topics.domain.port.Clock
import dev.kortex.myinfo.topics.domain.usecase.MarkEmbedBlocked
import dev.kortex.myinfo.topics.domain.usecase.RecordVideoProgress
import dev.kortex.myinfo.topics.domain.usecase.SetItemDone
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoProgressTest {

    private fun seen(vararg ranges: Pair<Int, Int>) =
        ranges.fold(SeenRanges.Empty) { acc, (start, end) -> acc + SeenRange(start, end) }

    // SeenRanges

    @Test
    fun `overlapping and touching stretches merge`() {
        assertEquals("0-190", seen(0 to 100, 50 to 190).encode())
        assertEquals("0-20", seen(0 to 10, 11 to 20).encode())
        assertEquals("0-10,12-20", seen(0 to 10, 12 to 20).encode())
        assertEquals("0-40", seen(30 to 40, 0 to 10, 12 to 20, 5 to 31).encode())
    }

    @Test
    fun `rewatching a stretch doesn't count it twice`() {
        val once = seen(0 to 120)
        assertEquals(120, (once + SeenRange(30, 90)).coveredSeconds)
        assertEquals(once, once + seen(0 to 120))
    }

    @Test
    fun `stretches out of order end up sorted`() {
        assertEquals("10-20,300-320", seen(300 to 320, 10 to 20).encode())
    }

    @Test
    fun `past the cap the closest pair is joined`() {
        val many = (0 until SeenRanges.MAX_RANGES + 1).fold(SeenRanges.Empty) { acc, i ->
            // Gaps of 10 s, except a 3 s one between the 6th and 7th.
            val start = i * 20 - if (i >= 6) 7 else 0
            acc + SeenRange(start, start + 10)
        }
        assertEquals(SeenRanges.MAX_RANGES, many.ranges.size)
        assertEquals(SeenRange(100, 123), many.ranges[5])
    }

    @Test
    fun `encoding reads back, and junk is skipped`() {
        val ranges = seen(0 to 190, 300 to 320)
        assertEquals(ranges, SeenRanges.decode(ranges.encode()))
        assertEquals("0-190,300-320", SeenRanges.decode("300-320, x-2 ,0-190,9-3,,").encode())
        assertEquals(SeenRanges.Empty, SeenRanges.decode(null))
        assertEquals(SeenRanges.Empty, SeenRanges.decode(""))
    }

    @Test
    fun `fraction clips to the length and needs one`() {
        assertEquals(0.5f, seen(0 to 50).fraction(100), 0.001f)
        assertEquals(1f, seen(0 to 130).fraction(100), 0.001f)
        assertEquals(0f, seen(0 to 50).fraction(null), 0.001f)
    }

    // VideoRecord.withPlayback

    private val fresh = VideoRecord(durationSeconds = null, watched = false, progress = null, embedBlocked = false)

    private fun report(vararg ranges: Pair<Int, Int>, resume: Int = ranges.lastOrNull()?.second ?: 0, length: Int? = 100, ended: Boolean = false) =
        PlaybackReport(resume, seen(*ranges), length, ended)

    @Test
    fun `the length is filled in, and corrected only when it is off by more than 2 s`() {
        assertEquals(100, fresh.withPlayback(report(0 to 5), NOW).durationSeconds)
        assertEquals(98, fresh.copy(durationSeconds = 98).withPlayback(report(0 to 5, length = 100), NOW).durationSeconds)
        assertEquals(103, fresh.copy(durationSeconds = 98).withPlayback(report(0 to 5, length = 103), NOW).durationSeconds)
        assertEquals(98, fresh.copy(durationSeconds = 98).withPlayback(report(0 to 5, length = null), NOW).durationSeconds)
    }

    @Test
    fun `stretches add up across saves and the resume point follows the player`() {
        val first = fresh.withPlayback(report(0 to 30), NOW)
        val second = first.withPlayback(report(60 to 70, resume = 70), NOW + 1)
        assertEquals("0-30,60-70", second.progress!!.seen.encode())
        assertEquals(70, second.progress!!.resumeSeconds)
        assertEquals(NOW + 1, second.progress!!.lastPlayedAtMillis)
    }

    @Test
    fun `crossing 90 percent marks it watched`() {
        val almost = fresh.withPlayback(report(0 to 89), NOW)
        assertFalse(almost.watched)
        assertTrue(almost.withPlayback(report(89 to 90), NOW).watched)
    }

    @Test
    fun `skipping ahead to the end doesn't count as watching it`() {
        assertFalse(fresh.withPlayback(report(0 to 10, 90 to 99, resume = 99), NOW).watched)
    }

    @Test
    fun `reaching the end marks it watched whatever was seen`() {
        assertTrue(fresh.withPlayback(report(95 to 100, ended = true), NOW).watched)
    }

    @Test
    fun `an unticked video past 90 percent isn't ticked again by the next save`() {
        val unticked = fresh.withPlayback(report(0 to 95), NOW).copy(watched = false)
        assertFalse(unticked.withPlayback(report(95 to 97), NOW).watched)
        assertTrue(unticked.withPlayback(report(97 to 100, ended = true), NOW).watched)
    }

    // VideoRecord.startSeconds

    @Test
    fun `playback resumes a little before where it stopped`() {
        assertEquals(0, fresh.startSeconds())
        assertEquals(317, fresh.copy(durationSeconds = 600, progress = progress(resume = 320)).startSeconds())
        assertEquals(0, fresh.copy(durationSeconds = 600, progress = progress(resume = 2)).startSeconds())
        assertEquals(317, fresh.copy(durationSeconds = null, progress = progress(resume = 320)).startSeconds())
    }

    @Test
    fun `a watched or nearly finished video starts from the top`() {
        assertEquals(0, fresh.copy(durationSeconds = 600, watched = true, progress = progress(resume = 320)).startSeconds())
        assertEquals(0, fresh.copy(durationSeconds = 600, progress = progress(resume = 570)).startSeconds())
    }

    private fun progress(resume: Int) = VideoProgress(resume, SeenRanges.Empty, lastPlayedAtMillis = NOW)

    // Use cases

    private val repository = FakeTopicsRepository()
    private val clock = Clock { NOW }
    private val record = RecordVideoProgress(repository, clock)

    @Test
    fun `record says when it just marked the video watched`() = runTest {
        repository.videos[1] = fresh
        assertFalse(record(1, report(0 to 50)))
        assertTrue(record(1, report(50 to 95)))
        assertFalse(record(1, report(95 to 100, ended = true)))
        assertEquals(NOW, repository.videos.getValue(1).progress!!.lastPlayedAtMillis)
    }

    @Test
    fun `undo keeps the progress and later saves don't tick it again`() = runTest {
        repository.videos[1] = fresh
        assertTrue(record(1, report(0 to 92)))

        SetItemDone(repository, clock)(1, done = false)
        assertFalse(repository.videos.getValue(1).watched)
        assertEquals(92, repository.videos.getValue(1).progress!!.seen.coveredSeconds)

        assertFalse(record(1, report(92 to 96)))
        assertFalse(repository.videos.getValue(1).watched)
    }

    @Test
    fun `record on something that isn't a video does nothing`() = runTest {
        assertFalse(record(9, report(0 to 100, ended = true)))
    }

    @Test
    fun `mark embed blocked keeps the rest of the record`() = runTest {
        repository.videos[1] = fresh.withPlayback(report(0 to 10), NOW)
        MarkEmbedBlocked(repository, clock)(1)
        assertTrue(repository.videos.getValue(1).embedBlocked)
        assertEquals(10, repository.videos.getValue(1).progress!!.resumeSeconds)
    }

    private companion object {
        const val NOW = 1_700_000_000_000L
    }
}
