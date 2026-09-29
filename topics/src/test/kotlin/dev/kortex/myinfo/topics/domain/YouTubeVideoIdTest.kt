package dev.kortex.myinfo.topics.domain

import dev.kortex.myinfo.topics.domain.usecase.YouTubeVideoId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class YouTubeVideoIdTest {
    private fun id(url: String) = YouTubeVideoId.parse(url)?.value

    @Test
    fun `every address DetectItemType calls a YouTube video`() {
        assertEquals(ID, id("youtube.com/watch?v=$ID"))
        assertEquals(ID, id("https://www.youtube.com/watch?v=$ID"))
        assertEquals(ID, id("https://m.youtube.com/watch?v=$ID"))
        assertEquals(ID, id("https://www.youtube.com/shorts/$ID"))
        assertEquals(ID, id("https://youtube.com/live/$ID"))
        assertEquals(ID, id("https://www.youtube.com/embed/$ID"))
        assertEquals(ID, id("https://youtu.be/$ID"))
    }

    @Test
    fun `other parameters and trailing parts are ignored`() {
        assertEquals(ID, id("https://www.youtube.com/watch?list=PL1&v=$ID&t=42s"))
        assertEquals(ID, id("https://youtu.be/$ID?si=abc&t=10"))
        assertEquals(ID, id("https://youtube.com/shorts/$ID/"))
        assertEquals(ID, id("https://music.youtube.com/watch?v=$ID"))
        assertEquals(ID, id("https://www.youtube-nocookie.com/embed/$ID"))
    }

    @Test
    fun `anything else has no id`() {
        assertNull(id("https://vimeo.com/123456"))
        assertNull(id("https://youtube.com/@channel"))
        assertNull(id("https://youtube.com/watch"))
        assertNull(id("https://youtube.com/watch?v="))
        assertNull(id("https://youtu.be/"))
        assertNull(id("https://youtu.be/short"))
        assertNull(id("https://example.com/watch?v=$ID"))
        assertNull(id("not an address"))
    }

    private companion object {
        const val ID = "dQw4w9WgXcQ"
    }
}
