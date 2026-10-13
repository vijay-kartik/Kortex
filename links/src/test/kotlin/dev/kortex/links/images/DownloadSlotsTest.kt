package dev.kortex.links.images

import dev.kortex.links.domain.model.LinkImageState
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class DownloadSlotsTest {

    @Test
    fun `downloads waiting for a slot don't use up their timeout`() = runTest {
        val slots = DownloadSlots(permits = 2, timeoutMillis = 1_000)
        var running = 0
        var mostRunning = 0
        val results = List(10) { i ->
            async {
                slots.run {
                    running++
                    mostRunning = maxOf(mostRunning, running)
                    delay(900)
                    running--
                    i
                }
            }
        }.awaitAll()

        assertEquals((0 until 10).toList(), results)
        assertEquals(2, mostRunning)
    }

    @Test
    fun `a download that runs too long once it has a slot times out`() = runTest {
        val slots = DownloadSlots(permits = 1, timeoutMillis = 1_000)
        try {
            slots.run { delay(1_001) }
            fail("Expected a timeout")
        } catch (e: TimeoutCancellationException) {
            // Expected.
        }
        // The timed-out download gave its slot back.
        assertEquals(1, slots.run { 1 })
    }

    @Test
    fun `a failed download is started again`() {
        assertTrue(needsRestart(LinkImageState.Failed) { true })
    }

    @Test
    fun `a running download is left alone`() {
        assertFalse(needsRestart(LinkImageState.Loading(null)) { false })
        assertFalse(needsRestart(LinkImageState.Loading(0.5f)) { false })
    }

    @Test
    fun `a finished download is started again only once its file is gone`() {
        val ready = LinkImageState.Ready("/cache/a.jpg", 100, 100)
        assertFalse(needsRestart(ready) { it == "/cache/a.jpg" })
        assertTrue(needsRestart(ready) { false })
    }
}
