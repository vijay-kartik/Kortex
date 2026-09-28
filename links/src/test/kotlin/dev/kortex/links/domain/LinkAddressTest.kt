package dev.kortex.links.domain

import dev.kortex.links.domain.model.linkDomain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LinkAddressTest {

    @Test
    fun `an http(s) address gives its host without www`() {
        assertEquals("example.com", linkDomain("https://www.example.com/post"))
        assertEquals("example.com", linkDomain("http://example.com"))
        assertEquals("blog.example.com", linkDomain("  https://blog.example.com:8080/a?b=c#d  "))
        // Lenient about characters some real addresses carry unescaped.
        assertEquals("example.com", linkDomain("https://example.com/a|b{c}"))
    }

    @Test
    fun `an address that isn't usable yet has none`() {
        listOf("", "example.com", "ftp://example.com", "https://", "https://localhost", "https://example.", "https://.example.com")
            .forEach { assertNull(it, linkDomain(it)) }
    }
}
