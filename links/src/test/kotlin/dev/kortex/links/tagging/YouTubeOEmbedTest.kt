package dev.kortex.links.tagging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class YouTubeOEmbedTest {

    @Test
    fun `a YouTube address is asked about through oEmbed`() {
        assertEquals(
            "https://www.youtube.com/oembed?format=json&url=https%3A%2F%2Fwww.youtube.com%2Fwatch%3Fv%3DXLUmuB1okWs",
            YouTubeOEmbed.endpoint("https://www.youtube.com/watch?v=XLUmuB1okWs"),
        )
        listOf(
            "https://youtube.com/watch?v=a",
            "https://m.youtube.com/watch?v=a",
            "https://music.youtube.com/watch?v=a",
            "https://youtu.be/a",
            " https://WWW.YouTube.com/shorts/a ",
        ).forEach { assertEquals(it, true, YouTubeOEmbed.endpoint(it)?.startsWith("https://www.youtube.com/oembed?")) }
    }

    @Test
    fun `other addresses have no oEmbed endpoint`() {
        listOf("https://example.com/watch?v=a", "https://notyoutube.com/a", "https://youtube.com.evil.com/a", "youtube.com/watch?v=a", "")
            .forEach { assertNull(it, YouTubeOEmbed.endpoint(it)) }
    }

    @Test
    fun `an oEmbed answer gives the title and thumbnail`() {
        val json = """
            {"title":" Some video ","author_name":"A channel","provider_name":"YouTube",
             "thumbnail_url":"https://i.ytimg.com/vi/XLUmuB1okWs/hqdefault.jpg","type":"video"}
        """.trimIndent()

        val metadata = YouTubeOEmbed.parse("https://youtu.be/XLUmuB1okWs", json)!!

        assertEquals("https://youtu.be/XLUmuB1okWs", metadata.url)
        assertEquals("Some video", metadata.title)
        assertEquals("YouTube", metadata.siteName)
        assertEquals("https://i.ytimg.com/vi/XLUmuB1okWs/hqdefault.jpg", metadata.imageUrl)
    }

    @Test
    fun `an answer without a usable thumbnail still gives the title`() {
        val metadata = YouTubeOEmbed.parse("https://youtu.be/a", """{"title":"Some video","thumbnail_url":null}""")!!

        assertEquals("Some video", metadata.title)
        assertNull(metadata.imageUrl)
        assertNull(YouTubeOEmbed.parse("https://youtu.be/a", """{"title":"Some video","thumbnail_url":"//i.ytimg.com/a.jpg"}""")!!.imageUrl)
    }

    @Test
    fun `an answer without a title or that isn't JSON gives nothing, so the page is read instead`() {
        assertNull(YouTubeOEmbed.parse("https://youtu.be/a", """{"thumbnail_url":"https://i.ytimg.com/a.jpg"}"""))
        assertNull(YouTubeOEmbed.parse("https://youtu.be/a", """{"title":null}"""))
        assertNull(YouTubeOEmbed.parse("https://youtu.be/a", """{"title":"  "}"""))
        assertNull(YouTubeOEmbed.parse("https://youtu.be/a", "Not Found"))
        assertNull(YouTubeOEmbed.parse("https://youtu.be/a", ""))
    }
}
