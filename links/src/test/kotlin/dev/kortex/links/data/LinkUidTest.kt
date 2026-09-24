package dev.kortex.links.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The browser extension derives the same uids; these vectors are the contract it must match
 * (docs/CLOUD_SYNC_PLAN.md › Identity).
 */
class LinkUidTest {

    @Test
    fun `uid is the first 32 hex chars of SHA-256 over the url key`() {
        mapOf(
            "example.com/post" to "1d990bb353ea3e44e1e1b66dc6c0b41f",
            "curaahome.com/products/curaa-automatic-pepper-grinder" to "6b8f106dce806fa11f62324e43644b49",
            "google.com" to "d4c9d9027326271a89ce51fcaf328ed6",
            "news.ycombinator.com/item?id=1" to "92237aeac6ebf5b11122597e3239e3dc",
            "münchen.de/straße" to "366d475401ab8f89b62848fb9221bd5d",
            "not a url" to "d8b5bf9b9fd4760c61234d12614d80c9",
        ).forEach { (urlKey, uid) -> assertEquals(urlKey, uid, linkUid(urlKey)) }
    }

    @Test
    fun `re-shares of one page get one uid`() {
        val uid = linkUid(linkUrlKey("https://example.com/post"))
        listOf(
            "https://www.Example.com/post/",
            "http://example.com/post?utm_source=x",
            "https://example.com/post#comments",
        ).forEach { assertEquals(it, uid, linkUid(linkUrlKey(it))) }
    }

    @Test
    fun `a new link row takes its uid from its address`() {
        val link = LinkEntity(url = "https://www.example.com/post/", title = "Post", createdAtMillis = 0)
        assertEquals("1d990bb353ea3e44e1e1b66dc6c0b41f", link.uid)
    }
}
