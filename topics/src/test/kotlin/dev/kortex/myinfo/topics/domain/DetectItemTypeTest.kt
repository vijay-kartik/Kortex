package dev.kortex.myinfo.topics.domain

import dev.kortex.myinfo.topics.domain.model.ItemType
import dev.kortex.myinfo.topics.domain.usecase.Detection
import dev.kortex.myinfo.topics.domain.usecase.DetectItemType
import org.junit.Assert.assertEquals
import org.junit.Test

class DetectItemTypeTest {
    private val detect = DetectItemType()

    @Test
    fun `youtube address without a scheme is a video`() {
        assertEquals(
            Detection(ItemType.Video, "https://youtube.com/watch?v=8xQ1n"),
            detect("youtube.com/watch?v=8xQ1n"),
        )
    }

    @Test
    fun `other video addresses`() {
        assertEquals(ItemType.Video, detect("https://www.youtube.com/shorts/abc").type)
        assertEquals(ItemType.Video, detect("https://m.youtube.com/watch?v=abc").type)
        assertEquals(ItemType.Video, detect("https://youtu.be/abc").type)
        assertEquals(ItemType.Video, detect("https://vimeo.com/123456").type)
    }

    @Test
    fun `video hosts outside a video page are links`() {
        assertEquals(ItemType.Link, detect("https://youtube.com/@channel").type)
        assertEquals(ItemType.Link, detect("https://youtu.be/").type)
        assertEquals(ItemType.Link, detect("https://vimeo.com/channels/staff").type)
    }

    @Test
    fun `file addresses are docs or images`() {
        assertEquals(ItemType.Doc, detect("https://example.com/visa-approval.PDF").type)
        assertEquals(ItemType.Image, detect("example.com/photos/receipt.jpg").type)
    }

    @Test
    fun `any other address is a link`() {
        assertEquals(Detection(ItemType.Link, "https://gov.uk/check-sponsor"), detect("  gov.uk/check-sponsor "))
        assertEquals(Detection(ItemType.Link, "http://example.com"), detect("http://example.com"))
    }

    @Test
    fun `text is a note`() {
        assertEquals(Detection(ItemType.Note, null), detect("Metro red line closes 00:30"))
        assertEquals(Detection(ItemType.Note, null), detect("see youtube.com/watch?v=1 later"))
        assertEquals(Detection(ItemType.Note, null), detect("https://"))
        assertEquals(Detection(ItemType.Note, null), detect(""))
    }
}
