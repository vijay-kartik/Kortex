package dev.kortex.finance.domain.read

import dev.kortex.finance.Fixtures.TODAY
import java.time.LocalDate
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TextReadingTest {
    private val sep28 = LocalDate.of(2026, 9, 28)

    @Test
    fun `amounts become paise, padding or truncating the fraction`() {
        assertEquals(48_557_50L, TextReading.minorOf("48,557.50"))
        assertEquals(5_420_00L, TextReading.minorOf("5,420"))
        assertEquals(42_50L, TextReading.minorOf("42.5"))
        assertEquals(50L, TextReading.minorOf(".50"))
        assertEquals(42_50L, TextReading.minorOf("42.509"))
    }

    @Test
    fun `malformed amounts are not numbers`() {
        assertNull(TextReading.minorOf(""))
        assertNull(TextReading.minorOf("1.2.3"))
        assertNull(TextReading.minorOf("12a.00"))
        assertNull(TextReading.minorOf("1234567890123"))
    }

    @Test
    fun `currency amounts in every spelling`() {
        mapOf(
            "Rs.42.50" to "42.50",
            "Rs 98,150" to "98,150",
            "INR 1,850.00" to "1,850.00",
            "₹5,420" to "5,420",
            "rs.10" to "10",
        ).forEach { (text, number) ->
            assertEquals(text, number, TextReading.CurrencyAmount.find(text)?.groupValues?.get(1))
        }
    }

    @Test
    fun `bare amounts need exactly two decimals and are not cut from longer numbers`() {
        assertEquals("1,239.00", TextReading.BareAmount.find("TOTAL 1,239.00")?.groupValues?.get(1))
        assertNull(TextReading.BareAmount.find("11,239.001"))
        assertNull(TextReading.BareAmount.find("1.239.00"))
        assertNull(TextReading.BareAmount.find("TOTAL 1,239.5"))
    }

    @Test
    fun `numeric dates read day first, or year first when it has four digits`() {
        listOf("28-09-26", "28/09/2026", "28.09.26", "2026-09-28").forEach { text ->
            assertEquals(text, sep28, TextReading.date(text, TODAY)?.value)
        }
    }

    @Test
    fun `named months with and without a year`() {
        listOf("28 Sep", "28th Sep", "28-Sept-26", "28 September, 2026", "28 Sep '26").forEach { text ->
            assertEquals(text, sep28, TextReading.date(text, TODAY)?.value)
        }
    }

    @Test
    fun `a date without a year stays on or before today`() {
        assertEquals(LocalDate.of(2026, 12, 31), TextReading.date("31 Dec", LocalDate.of(2027, 1, 1))?.value)
        assertEquals(sep28, TextReading.date("28 Sep", sep28)?.value)
    }

    @Test
    fun `invalid dates are skipped`() {
        assertEquals(LocalDate.of(2026, 3, 1), TextReading.date("on 31-02-26, settled 01-03-26", TODAY)?.value)
    }

    @Test
    fun `the date earliest in the text wins`() {
        val text = "Refund for 28 Sep credited on 01-10-26"
        val found = TextReading.date(text, TODAY)!!
        assertEquals(sep28, found.value)
        assertEquals("28 Sep", text.substring(found.range))
    }

    @Test
    fun `amounts and times are not dates`() {
        assertNull(TextReading.date("Rs.1,850.00", TODAY))
        assertNull(TextReading.date("18:42", TODAY))
    }

    @Test
    fun `times in 24 and 12 hour clocks`() {
        val at1842 = LocalTime.of(18, 42)
        listOf("18:42", "06:42:10 pm", "6:42 P.M.").forEach { text ->
            assertEquals(text, at1842, TextReading.time(text)?.value)
        }
        assertEquals(LocalTime.of(0, 5), TextReading.time("12:05 AM")?.value)
        assertEquals(LocalTime.of(12, 5), TextReading.time("12:05 PM")?.value)
    }

    @Test
    fun `invalid times are skipped`() {
        assertEquals(LocalTime.of(18, 42), TextReading.time("25:10 then 18:42")?.value)
        assertNull(TextReading.time("no time here"))
    }
}
