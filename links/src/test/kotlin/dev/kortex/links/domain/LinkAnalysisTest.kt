package dev.kortex.links.domain

import dev.kortex.links.domain.model.LinkAnalysis
import dev.kortex.links.domain.model.LinkImageSource
import dev.kortex.links.domain.model.PageReadPhase
import org.junit.Assert.assertEquals
import org.junit.Test

class LinkAnalysisTest {

    @Test
    fun `only a page read for this address knows its image`() {
        val read = LinkAnalysis(URL, phase = PageReadPhase.SuggestingTags, imageUrl = IMAGE)

        assertEquals(LinkImageSource.Known(IMAGE), read.imageSourceFor(URL))
        assertEquals(LinkImageSource.Known(IMAGE), read.copy(phase = PageReadPhase.Done).imageSourceFor(URL))
        assertEquals(LinkImageSource.None, read.copy(imageUrl = null).imageSourceFor(URL))
        assertEquals(LinkImageSource.Unknown, read.imageSourceFor("https://example.com/other"))
        assertEquals(LinkImageSource.Unknown, read.copy(phase = PageReadPhase.Idle).imageSourceFor(URL))
        assertEquals(LinkImageSource.Unknown, read.copy(phase = PageReadPhase.ReadingPage, imageUrl = null).imageSourceFor(URL))
    }

    private companion object {
        const val URL = "https://example.com/post"
        const val IMAGE = "https://example.com/post.jpg"
    }
}
