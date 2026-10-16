package dev.kortex.app.domain.share

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

/** [SharedUriPolicy]: only other apps' `content://` photos and PDFs, within the size cap. */
class SharedUriPolicyTest {
    private val own = "dev.kortex.app"
    private val owners = mapOf(
        "com.android.providers.media.documents" to "com.android.providers.media",
        "dev.kortex.app.topics.fileprovider" to own,
        "aliased.kortex.provider" to own,
    )

    private fun allowed(scheme: String?, authority: String?) =
        SharedUriPolicy.isAllowedSource(scheme, authority, own) { owners[it] }

    @Test
    fun `another app's content provider is allowed`() {
        assertTrue(allowed("content", "com.android.providers.media.documents"))
    }

    @Test
    fun `file and other schemes are rejected`() {
        assertFalse(allowed("file", null))
        assertFalse(allowed("file", ""))
        assertFalse(allowed("android.resource", own))
        assertFalse(allowed("CONTENT", "com.android.providers.media.documents"))
        assertFalse(allowed(null, "com.android.providers.media.documents"))
    }

    @Test
    fun `kortex's own providers are rejected`() {
        assertFalse(allowed("content", "dev.kortex.app.topics.fileprovider"))
        assertFalse(allowed("content", "dev.kortex.app.anything"))
        assertFalse(allowed("content", "aliased.kortex.provider"))
    }

    @Test
    fun `a user-id prefix does not hide kortex's own provider`() {
        assertFalse(allowed("content", "0@dev.kortex.app.topics.fileprovider"))
        assertFalse(allowed("content", "0@aliased.kortex.provider"))
        assertTrue(allowed("content", "0@com.android.providers.media.documents"))
    }

    @Test
    fun `a missing authority is rejected`() {
        assertFalse(allowed("content", null))
        assertFalse(allowed("content", "0@"))
    }

    @Test
    fun `only images and pdfs pass the mime check`() {
        assertTrue(SharedUriPolicy.isAllowedMimeType("image/jpeg"))
        assertTrue(SharedUriPolicy.isAllowedMimeType("IMAGE/PNG"))
        assertTrue(SharedUriPolicy.isAllowedMimeType("application/pdf"))
        assertFalse(SharedUriPolicy.isAllowedMimeType("application/octet-stream"))
        assertFalse(SharedUriPolicy.isAllowedMimeType("text/plain"))
        assertFalse(SharedUriPolicy.isAllowedMimeType(null))
    }

    @Test
    fun `declared size over the cap is refused, unknown size is left to the bounded read`() {
        assertTrue(SharedUriPolicy.fitsSizeCap(SharedUriPolicy.MAX_BYTES))
        assertFalse(SharedUriPolicy.fitsSizeCap(SharedUriPolicy.MAX_BYTES + 1))
        assertTrue(SharedUriPolicy.fitsSizeCap(null))
    }

    @Test
    fun `bounded read returns the bytes up to the cap and null past it`() {
        val data = ByteArray(10) { it.toByte() }
        assertArrayEquals(data, SharedUriPolicy.readCapped(ByteArrayInputStream(data), max = 10))
        assertNull(SharedUriPolicy.readCapped(ByteArrayInputStream(data), max = 9))
    }
}
