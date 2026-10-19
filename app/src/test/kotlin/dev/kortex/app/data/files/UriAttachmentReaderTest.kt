package dev.kortex.app.data.files

import dev.kortex.core.state.Attachments
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InputStream

class UriAttachmentReaderTest {

    /** An endless stream that counts how much of it was read. */
    private class EndlessStream : InputStream() {
        var read = 0L
        override fun read(): Int = 0.also { read++ }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            b.fill(0, off, off + len)
            read += len
            return len
        }
    }

    @Test
    fun `a file under the limit attaches whole, base64-encoded`() {
        val read = readAttachment("pic.png", 3, "image/png", maxBytes = 10) {
            byteArrayOf(1, 2, 3).inputStream()
        }

        val attachment = (read as AttachmentRead.Ok).attachment
        assertEquals("image/png", attachment.mimeType)
        assertEquals("AQID", attachment.dataBase64)
        assertEquals("pic.png", attachment.filename)
    }

    @Test
    fun `a file exactly at the limit still attaches`() {
        val read = readAttachment("a.bin", null, "application/octet-stream", maxBytes = 4) {
            ByteArray(4).inputStream()
        }

        assertTrue(read is AttachmentRead.Ok)
    }

    @Test
    fun `a known size over the limit is refused without opening the stream`() {
        var opened = false
        val read = readAttachment("clip.mp4", 120L * 1024 * 1024, "video/mp4", Attachments.MAX_BYTES) {
            opened = true
            EndlessStream()
        }

        assertEquals(AttachmentRead.TooLarge("clip.mp4", 120L * 1024 * 1024, Attachments.MAX_BYTES), read)
        assertEquals("clip.mp4 is 120 MB; the limit is 10 MB.", (read as AttachmentRead.TooLarge).message)
        assertFalse(opened)
    }

    @Test
    fun `an unknown size over the limit is cut off just past the limit`() {
        val stream = EndlessStream()
        val read = readAttachment("clip.mp4", null, "video/mp4", Attachments.MAX_BYTES) { stream }

        assertEquals(AttachmentRead.TooLarge("clip.mp4", null, Attachments.MAX_BYTES), read)
        assertEquals("clip.mp4 is over the 10 MB limit.", (read as AttachmentRead.TooLarge).message)
        assertEquals(Attachments.MAX_BYTES + 1, stream.read)
    }

    @Test
    fun `a size the provider under-reports is still bounded by the read`() {
        val stream = EndlessStream()
        val read = readAttachment("clip.mp4", 5, "video/mp4", maxBytes = 1_000) { stream }

        assertTrue(read is AttachmentRead.TooLarge)
        assertEquals(1_001L, stream.read)
    }

    @Test
    fun `a stream that can't be opened is unreadable`() {
        assertEquals(AttachmentRead.Unreadable, readAttachment("x", null, "text/plain", 10) { null })
        assertEquals(
            AttachmentRead.Unreadable,
            readAttachment("x", null, "text/plain", 10) { throw java.io.FileNotFoundException() },
        )
    }
}
