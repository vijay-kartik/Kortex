package dev.kortex.myinfo.topics.domain

import dev.kortex.myinfo.topics.domain.model.PlaybackReport
import dev.kortex.myinfo.topics.domain.model.PlaybackState
import dev.kortex.myinfo.topics.domain.model.WatchSession
import dev.kortex.myinfo.topics.domain.port.Clock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchSessionTest {
    private var now = 0L
    private val clock = Clock { now }

    /** Ticks once a video second from [from] to [to], each [wallMillis] of wall time apart; collects reports. */
    private fun WatchSession.play(from: Int, to: Int, step: Float = 1f, wallMillis: Long = 100): List<PlaybackReport> {
        val reports = mutableListOf<PlaybackReport>()
        var second = from.toFloat()
        while (second <= to) {
            now += wallMillis
            onSecond(second)?.let(reports::add)
            second += step
        }
        return reports
    }

    private fun session(start: Int = 0) = WatchSession(clock, start).apply { onLength(100f) }

    @Test
    fun `playing then pausing reports the stretch and where it stopped`() {
        val session = session()
        session.onState(PlaybackState.Playing)
        session.play(0, 12)
        val report = session.onState(PlaybackState.Paused)!!

        assertEquals("0-12", report.seen.encode())
        assertEquals(12, report.resumeSeconds)
        assertEquals(100, report.lengthSeconds)
        assertFalse(report.ended)
    }

    @Test
    fun `a pause with nothing new reports nothing`() {
        val session = session()
        session.onState(PlaybackState.Playing)
        session.play(0, 5)
        assertNotNull(session.onState(PlaybackState.Paused))
        session.onState(PlaybackState.Playing)
        assertNull(session.onState(PlaybackState.Paused))
        assertNull(session.close())
    }

    @Test
    fun `seeking forward starts a new stretch and skips what was jumped`() {
        val session = session()
        session.onState(PlaybackState.Playing)
        session.play(0, 10)
        session.play(40, 50)
        assertEquals("0-10,40-50", session.onState(PlaybackState.Paused)!!.seen.encode())
    }

    @Test
    fun `seeking back rewatches without counting twice`() {
        val session = session()
        session.onState(PlaybackState.Playing)
        session.play(0, 30)
        session.play(10, 20)
        val report = session.onState(PlaybackState.Paused)!!
        assertEquals(30, report.seen.coveredSeconds)
        assertEquals(20, report.resumeSeconds)
    }

    @Test
    fun `at 2x the bigger steps still count as playing on`() {
        val session = session()
        session.onRate(2f)
        session.onState(PlaybackState.Playing)
        session.play(0, 40, step = 4f)
        assertEquals("0-40", session.onState(PlaybackState.Paused)!!.seen.encode())
    }

    @Test
    fun `at 1x a 4 second step is a jump`() {
        val session = session()
        session.onState(PlaybackState.Playing)
        session.play(0, 8, step = 4f)
        assertEquals(0, session.onState(PlaybackState.Paused)!!.seen.coveredSeconds)
    }

    @Test
    fun `an ad holds the clock still and adds nothing`() {
        val session = session()
        session.onState(PlaybackState.Playing)
        session.play(0, 10)
        repeat(30) { now += 100; session.onSecond(10f) }
        session.play(11, 20)
        assertEquals("0-20", session.onState(PlaybackState.Paused)!!.seen.encode())
    }

    @Test
    fun `ticks while paused move the resume point but count nothing`() {
        val session = session()
        session.onState(PlaybackState.Paused)
        session.onSecond(60f)
        val report = session.close()!!
        assertEquals(60, report.resumeSeconds)
        assertEquals(0, report.seen.coveredSeconds)
    }

    @Test
    fun `while playing it reports every 15 seconds, each time only what is new`() {
        val session = session()
        session.onState(PlaybackState.Playing)
        val reports = session.play(0, 40, wallMillis = 1_000)
        assertEquals(2, reports.size)
        assertEquals("0-14", reports[0].seen.encode())
        assertEquals("14-29", reports[1].seen.encode())
        assertEquals("29-40", session.close()!!.seen.encode())
    }

    @Test
    fun `the end reports the whole length and ended`() {
        val session = session(start = 90)
        session.onState(PlaybackState.Playing)
        session.play(90, 99)
        val report = session.onState(PlaybackState.Ended)!!
        assertTrue(report.ended)
        assertEquals(100, report.resumeSeconds)
        assertEquals("90-99", report.seen.encode())
        assertNull(session.close())
    }

    @Test
    fun `buffering closes the stretch and playing on joins it back up`() {
        val session = session()
        session.onState(PlaybackState.Playing)
        session.play(0, 10)
        session.onState(PlaybackState.Buffering)
        session.onState(PlaybackState.Playing)
        session.play(11, 20)
        assertEquals("0-20", session.close()!!.seen.encode())
    }

    @Test
    fun `opening and leaving without playing saves nothing`() {
        val session = session(start = 30)
        session.onState(PlaybackState.Unstarted)
        assertNull(session.close())
    }
}
