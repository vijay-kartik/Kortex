package dev.kortex.app.domain.chat

import dev.kortex.core.state.Attachment
import org.junit.Assert.assertEquals
import org.junit.Test

/** [sessionTitle]: the query when there is one, otherwise what was attached. */
class SessionTitleTest {
    private fun attachment(mimeType: String, filename: String? = null) =
        Attachment(mimeType = mimeType, dataBase64 = "", filename = filename)

    @Test
    fun `the query is the title`() {
        assertEquals("What did I spend?", sessionTitle("  What did I spend?  ", listOf(attachment("image/png", "a.png"))))
    }

    @Test
    fun `an attachment-only message is titled by the file name`() {
        assertEquals("receipt.pdf", sessionTitle("", listOf(attachment("application/pdf", "receipt.pdf"))))
    }

    @Test
    fun `an unnamed attachment is titled by its kind`() {
        assertEquals("Shared photo", sessionTitle(" ", listOf(attachment("image/jpeg"))))
        assertEquals("Shared PDF", sessionTitle("", listOf(attachment("application/pdf"))))
        assertEquals("Shared file", sessionTitle("", listOf(attachment("text/plain"))))
    }

    @Test
    fun `nothing at all gives a blank title`() {
        assertEquals("", sessionTitle("", emptyList()))
    }
}
