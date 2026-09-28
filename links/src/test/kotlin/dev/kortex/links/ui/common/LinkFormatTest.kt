package dev.kortex.links.ui.common

import dev.kortex.links.domain.model.Link
import org.junit.Assert.assertEquals
import org.junit.Test

class LinkFormatTest {

    @Test
    fun `relative age`() {
        val now = 800L * DAY
        assertEquals("NOW", relativeAge(now - 30_000, now))
        assertEquals("5M AGO", relativeAge(now - 5 * MINUTE, now))
        assertEquals("3H AGO", relativeAge(now - 3 * HOUR, now))
        assertEquals("3D AGO", relativeAge(now - 3 * DAY, now))
        assertEquals("2W AGO", relativeAge(now - 14 * DAY, now))
        assertEquals("4MO AGO", relativeAge(now - 125 * DAY, now))
        assertEquals("1Y AGO", relativeAge(now - 366 * DAY, now))
        assertEquals("a clock that moved back reads as now", "NOW", relativeAge(now + HOUR, now))
    }

    @Test
    fun `a link without a title shows its host`() {
        val link = Link(1, "https://www.example.com/post", title = "", createdAtMillis = 0, thumbnailPath = null, tags = emptyList())

        assertEquals("example.com", link.displayTitle())
        assertEquals("A post", link.copy(title = "A post").displayTitle())
        assertEquals("not an address", link.copy(url = "not an address").displayTitle())
    }

    private companion object {
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
        const val DAY = 24 * HOUR
    }
}
