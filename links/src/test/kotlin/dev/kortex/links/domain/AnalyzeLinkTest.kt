package dev.kortex.links.domain

import dev.kortex.links.domain.model.LinkAnalysis
import dev.kortex.links.domain.model.LinkImageState
import dev.kortex.links.domain.model.PageMetadata
import dev.kortex.links.domain.model.PageReadPhase
import dev.kortex.links.domain.usecase.AnalyzeLink
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AnalyzeLinkTest {
    private val pages = FakePageReader()
    private val suggester = FakeTagSuggester()
    private val images = FakeImageDownloads()
    private val analyze = AnalyzeLink(pages, suggester, images)

    @Test
    fun `an unusable address stays idle and reads nothing`() = runTest {
        assertEquals(listOf(LinkAnalysis("example")), analyze("example").toList())
        assertTrue(pages.reads.isEmpty())
    }

    @Test
    fun `phases run reading, suggesting, done`() = runTest {
        pages.pages[URL] = PageMetadata(URL, title = "Pepper grinder", imageUrl = IMAGE, keywords = listOf("homeware"))
        val pageRead = CompletableDeferred<Unit>().also { pages.gates[URL] = it }
        val suggested = CompletableDeferred<Unit>().also { suggester.gate = it }
        suggester.suggestions = listOf("kitchen")
        val seen = mutableListOf<LinkAnalysis>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { analyze(URL).toList(seen) }

        assertEquals(LinkAnalysis(URL, phase = PageReadPhase.ReadingPage), seen.last())

        pageRead.complete(Unit)
        runCurrent()
        with(seen.last()) {
            assertEquals(PageReadPhase.SuggestingTags, phase)
            assertEquals("Pepper grinder", suggestedTitle)
            assertEquals(IMAGE, imageUrl)
            assertEquals(LinkImageState.Loading(null), image)
            assertTrue("homeware" in candidateTags)
            assertTrue(suggestedTags.isEmpty())
        }

        suggested.complete(Unit)
        runCurrent()
        assertEquals(PageReadPhase.Done, seen.last().phase)
        assertEquals(listOf("kitchen"), seen.last().suggestedTags)
        assertEquals(
            listOf(PageReadPhase.ReadingPage, PageReadPhase.SuggestingTags, PageReadPhase.Done),
            seen.map { it.phase }.distinct(),
        )
    }

    @Test
    fun `a page without an image finishes, and no suggestions still means done`() = runTest {
        pages.pages[URL] = PageMetadata(URL, title = "Pepper grinder")

        val seen = analyze(URL).toList()

        assertEquals(PageReadPhase.ReadingPage, seen.first().phase)
        with(seen.last()) {
            assertEquals(PageReadPhase.Done, phase)
            assertTrue(suggestedTags.isEmpty())
            assertNull(imageUrl)
            assertNull(image)
        }
    }

    @Test
    fun `the image keeps the analysis open, so a retry shows up`() = runTest {
        pages.pages[URL] = PageMetadata(URL, imageUrl = IMAGE)
        val seen = mutableListOf<LinkAnalysis>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { analyze(URL).toList(seen) }
        val download = images.download(IMAGE)

        download.value = LinkImageState.Failed
        runCurrent()
        assertEquals(LinkImageState.Failed, seen.last().image)

        download.value = LinkImageState.Ready("/cache/a.jpg", width = 800, height = 600)
        runCurrent()
        assertEquals(LinkImageState.Ready("/cache/a.jpg", 800, 600), seen.last().image)
        assertEquals(PageReadPhase.Done, seen.last().phase)
    }

    private companion object {
        const val URL = "https://curaahome.com/products/curaa-automatic-pepper-grinder"
        const val IMAGE = "https://cdn.curaahome.com/grinder.jpg"
    }
}
