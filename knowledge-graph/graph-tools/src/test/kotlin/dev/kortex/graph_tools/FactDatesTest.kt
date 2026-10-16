package dev.kortex.graph_tools

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class FactDatesTest {

    private val zone = ZoneId.of("Asia/Kolkata")
    private val dates = FactDates(zone)

    private fun millis(dateTime: LocalDateTime) = dateTime.atZone(zone).toInstant().toEpochMilli()

    @Test
    fun `phone numbers and booking references are not timestamps`() {
        assertEquals(0L, dates.parseExplicitTimestamp("01146462000"))
        assertEquals(0L, dates.parseExplicitTimestamp("987654321"))
        assertEquals(0L, dates.parseDateLike("01146462000"))
    }

    @Test
    fun `epoch millis in the plausible window are accepted in date fields`() {
        assertEquals(1_752_658_200_000L, dates.parseExplicitTimestamp("1752658200000"))
        // ...but never when inspecting a value that should not be a date.
        assertEquals(0L, dates.parseDateLike("1752658200000"))
    }

    @Test
    fun `d-MMM-yyyy HH mm parses in the given zone`() {
        val expected = millis(LocalDateTime.of(2025, 7, 16, 15, 0))
        assertEquals(expected, dates.parseDateLike("16-Jul-2025 15:00"))
        assertEquals(expected, dates.parseDateLike("16-Jul-2025, 15:00"))
    }

    @Test
    fun `ISO inputs parse in the given zone`() {
        assertEquals(millis(LocalDateTime.of(2025, 7, 16, 15, 0)), dates.parseDateLike("2025-07-16T15:00"))
        assertEquals(
            LocalDate.of(2025, 7, 16).atStartOfDay(zone).toInstant().toEpochMilli(),
            dates.parseDateLike("2025-07-16"),
        )
        // An explicit offset wins over the zone.
        assertEquals(1_752_658_200_000L, dates.parseDateLike("2025-07-16T09:30:00Z"))
    }

    @Test
    fun `iso renders back in the given zone`() {
        assertEquals("2025-07-16T15:00", dates.iso(millis(LocalDateTime.of(2025, 7, 16, 15, 0))))
    }

    @Test
    fun `free text is not a date`() {
        assertEquals(0L, dates.parseExplicitTimestamp("next Tuesday"))
    }
}
