package dev.kortex.app.ui.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShareResultNotificationsTest {
    private val answer = "Your HDFC statement shows ₹42,000 spent in October. ".repeat(10)

    @Test
    fun `shows the title and the start of the answer`() {
        val details = ShareResultNotifications.details("HDFC statement", answer, redact = false)
        assertEquals(ShareResultNotifications.Details("HDFC statement", answer.take(300)), details)
    }

    @Test
    fun `with App lock on, shows neither the title nor the answer`() {
        assertNull(ShareResultNotifications.details("HDFC statement", answer, redact = true))
    }
}
