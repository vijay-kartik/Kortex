package dev.kortex.sync.links

import dev.kortex.links.data.LinkEntity
import dev.kortex.links.data.LinkWithTags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkDocsTest {

    @Test
    fun `a pushed link reads back as the same link`() {
        val entity = LinkEntity(
            url = "https://www.example.com/post/?utm_source=x",
            title = "A post",
            createdAtMillis = 1_000,
            imageUrl = "https://example.com/og.png",
            imageHidden = true,
            updatedAtMillis = 2_000,
        )
        val doc = linkDoc(LinkWithTags(entity, listOf("read", "later")), serverTime = "ts")

        val remote = remoteLink(entity.uid, doc)!!
        assertEquals(entity.url, remote.url)
        assertEquals("A post", remote.title)
        assertEquals(listOf("read", "later"), remote.tags)
        assertEquals(entity.imageUrl, remote.imageUrl)
        assertTrue(remote.imageHidden)
        assertEquals(1_000L, remote.createdAtMillis)
        assertEquals(2_000L, remote.updatedAtMillis)
        assertEquals(false, remote.deleted)
        assertEquals("example.com/post", doc[LinkFields.URL_KEY])
    }

    @Test
    fun `a delete written over a link that was never pushed still reads as a delete`() {
        val remote = remoteLink("abc", deletedLinkDoc(deletedAtMillis = 5_000, serverTime = "ts"))!!
        assertTrue(remote.deleted)
        assertEquals(5_000L, remote.updatedAtMillis)
    }

    @Test
    fun `a live link without an address is skipped`() {
        assertNull(remoteLink("abc", mapOf(LinkFields.UPDATED_AT to 1L, LinkFields.TITLE to "x")))
    }

    @Test
    fun `a document without updatedAt is skipped`() {
        assertNull(remoteLink("abc", mapOf(LinkFields.URL to "https://example.com")))
    }

    @Test
    fun `sparse documents from other clients get defaults`() {
        // Numbers written from JavaScript can arrive as doubles.
        val remote = remoteLink(
            "abc",
            mapOf(LinkFields.URL to "https://example.com", LinkFields.UPDATED_AT to 3_000.0, LinkFields.TAGS to listOf("a", 1, null)),
        )!!
        assertEquals("https://example.com", remote.title)
        assertEquals(listOf("a"), remote.tags)
        assertEquals(3_000L, remote.createdAtMillis)
        assertNull(remote.imageUrl)
    }
}
