package dev.kortex.links.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class LinkUrlKeyTest {

    @Test
    fun `re-shares of one page share a key`() {
        val key = linkUrlKey("https://example.com/post")
        listOf(
            "https://www.Example.com/post/",
            "http://example.com/post",
            "https://example.com/post#comments",
            "https://example.com/post?utm_source=x&utm_medium=social",
            "https://example.com/post?fbclid=abc",
            "https://example.com:443/post",
            "  https://example.com/post  ",
        ).forEach { assertEquals(it, key, linkUrlKey(it)) }
    }

    @Test
    fun `meaningful differences keep keys apart`() {
        val key = linkUrlKey("https://example.com/post")
        listOf(
            "https://example.com/post?id=2",
            "https://example.com/Post",
            "https://example.com/post/2",
            "https://example.com:8080/post",
            "https://blog.example.com/post",
        ).forEach { assertNotEquals(it, key, linkUrlKey(it)) }
    }

    @Test
    fun `tracking params are dropped but others keep their order`() {
        assertEquals(
            "example.com/search?q=room&page=2",
            linkUrlKey("https://example.com/search?utm_campaign=x&q=room&gclid=y&page=2"),
        )
    }

    @Test
    fun `root path and bare host match`() {
        assertEquals(linkUrlKey("https://example.com"), linkUrlKey("https://example.com/"))
    }

    @Test
    fun `non-web input is only trimmed`() {
        assertEquals("not a url", linkUrlKey(" not a url "))
        assertEquals("mailto:a@b.com", linkUrlKey("mailto:a@b.com"))
    }
}
