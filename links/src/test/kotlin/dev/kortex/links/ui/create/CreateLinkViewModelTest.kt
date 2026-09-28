package dev.kortex.links.ui.create

import dev.kortex.links.domain.FakeImageDownloads
import dev.kortex.links.domain.FakeLinksRepository
import dev.kortex.links.domain.FakePageReader
import dev.kortex.links.domain.FakeTagSuggester
import dev.kortex.links.domain.model.AlreadySavedLink
import dev.kortex.links.domain.model.Link
import dev.kortex.links.domain.model.LinkDraft
import dev.kortex.links.domain.model.LinkImageSource
import dev.kortex.links.domain.model.LinkImageState
import dev.kortex.links.domain.model.PageMetadata
import dev.kortex.links.domain.model.PageReadPhase
import dev.kortex.links.domain.model.TagCount
import dev.kortex.links.domain.port.Clock
import dev.kortex.links.domain.usecase.AnalyzeLink
import dev.kortex.links.domain.usecase.CreateTag
import dev.kortex.links.domain.usecase.ObserveDuplicate
import dev.kortex.links.domain.usecase.ObserveTagNames
import dev.kortex.links.domain.usecase.RetryLinkImage
import dev.kortex.links.domain.usecase.SaveLink
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The new-link form's ViewModel on virtual time, so the 600 ms debounce can be stepped through. */
@OptIn(ExperimentalCoroutinesApi::class)
class CreateLinkViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val repository = FakeLinksRepository()
    private val pages = FakePageReader()
    private val suggester = FakeTagSuggester()
    private val images = FakeImageDownloads()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        repository.tagCounts.value = listOf(TagCount("Kitchen", 0), TagCount("Reading", 0))
        pages.pages[GRINDER] = PageMetadata(GRINDER, title = "Pepper grinder", imageUrl = IMAGE)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ── Reading the page ──────────────────────────────────────────

    @Test
    fun `the page is read only once typing has settled`() = runTest(dispatcher) {
        val viewModel = viewModel()

        viewModel.onIntent(CreateLinkIntent.UrlChanged(GRINDER))
        advanceTimeBy(DEBOUNCE_MS - 1)
        runCurrent()
        assertTrue("not a moment early", pages.reads.isEmpty())

        advanceTimeBy(2)
        runCurrent()
        assertEquals(listOf(GRINDER), pages.reads)
        assertEquals(PageReadPhase.Done, viewModel.state.value.phaseFor(GRINDER))
        assertEquals("Pepper grinder", viewModel.state.value.titleSuggestionFor(GRINDER))
    }

    @Test
    fun `typing and undoing within the debounce doesn't read the page again`() = runTest(dispatcher) {
        val viewModel = viewModel()
        viewModel.onIntent(CreateLinkIntent.UrlChanged(GRINDER))
        advanceTimeBy(DEBOUNCE_MS + 1)
        runCurrent()

        viewModel.onIntent(CreateLinkIntent.UrlChanged("${GRINDER}x"))
        advanceTimeBy(DEBOUNCE_MS / 2)
        viewModel.onIntent(CreateLinkIntent.UrlChanged(GRINDER))
        advanceTimeBy(DEBOUNCE_MS * 2)
        runCurrent()

        assertEquals(listOf(GRINDER), pages.reads)
    }

    @Test
    fun `a newer address cancels the older read, which can't land after it`() = runTest(dispatcher) {
        val slowRead = CompletableDeferred<Unit>().also { pages.gates[GRINDER] = it }
        val viewModel = viewModel()
        viewModel.onIntent(CreateLinkIntent.UrlChanged(GRINDER))
        advanceTimeBy(DEBOUNCE_MS + 1)
        runCurrent()
        assertEquals(PageReadPhase.ReadingPage, viewModel.state.value.phaseFor(GRINDER))

        viewModel.onIntent(CreateLinkIntent.UrlChanged(OTHER))
        advanceTimeBy(DEBOUNCE_MS + 1)
        runCurrent()
        slowRead.complete(Unit)
        runCurrent()

        assertEquals(OTHER, viewModel.state.value.analysis.url)
        assertEquals(PageReadPhase.Done, viewModel.state.value.phaseFor(OTHER))
    }

    @Test
    fun `the duplicate check keeps up with typing`() = runTest(dispatcher) {
        repository.links.value = listOf(Link(1, GRINDER, "Pepper grinder", 0, thumbnailPath = null, tags = emptyList()))
        val viewModel = viewModel()

        viewModel.onIntent(CreateLinkIntent.UrlChanged(GRINDER))
        runCurrent()

        assertEquals(AlreadySavedLink(GRINDER, "Pepper grinder"), viewModel.state.value.duplicateOf(GRINDER))
        assertFalse(viewModel.state.value.canSave(GRINDER))
    }

    // ── Tags ──────────────────────────────────────────────────────

    @Test
    fun `a typed tag in another case selects the existing one`() = runTest(dispatcher) {
        val viewModel = viewModel()
        viewModel.onIntent(CreateLinkIntent.StartNewTag)

        viewModel.onIntent(CreateLinkIntent.AddTag(" kitchen "))
        runCurrent()

        assertEquals(listOf("Kitchen"), viewModel.state.value.selectedTags)
        assertFalse(viewModel.state.value.addingTag)
        assertEquals("nothing created", listOf("Kitchen", "Reading"), repository.tagCounts.value.map { it.name })
    }

    @Test
    fun `a brand-new tag is created at once and selected`() = runTest(dispatcher) {
        val viewModel = viewModel()
        viewModel.onIntent(CreateLinkIntent.StartNewTag)

        viewModel.onIntent(CreateLinkIntent.AddTag("Homeware"))
        runCurrent()

        assertEquals(listOf("Homeware"), viewModel.state.value.selectedTags)
        assertTrue("Homeware" in repository.tagCounts.value.map { it.name })
        assertTrue(viewModel.state.value.tags.contains("Homeware"))
    }

    @Test
    fun `a blank tag name only closes the field`() = runTest(dispatcher) {
        val viewModel = viewModel()
        viewModel.onIntent(CreateLinkIntent.StartNewTag)

        viewModel.onIntent(CreateLinkIntent.AddTag("   "))

        assertFalse(viewModel.state.value.addingTag)
        assertTrue(viewModel.state.value.selectedTags.isEmpty())
    }

    // ── The page's image ──────────────────────────────────────────

    @Test
    fun `showing a failed image retries it`() = runTest(dispatcher) {
        val viewModel = analysedViewModel()
        images.download(IMAGE).value = LinkImageState.Failed
        runCurrent()

        viewModel.onIntent(CreateLinkIntent.SetImageHidden(true))
        assertTrue("hiding doesn't retry", images.retries.isEmpty())
        viewModel.onIntent(CreateLinkIntent.SetImageHidden(false))

        assertEquals(listOf(IMAGE), images.retries)
        assertFalse(viewModel.state.value.imageHidden)
    }

    @Test
    fun `the hide choice resets when another address is analysed`() = runTest(dispatcher) {
        val viewModel = analysedViewModel()
        viewModel.onIntent(CreateLinkIntent.SetImageHidden(true))

        viewModel.onIntent(CreateLinkIntent.UrlChanged(OTHER))
        advanceTimeBy(DEBOUNCE_MS + 1)
        runCurrent()

        assertFalse(viewModel.state.value.imageHidden)
    }

    // ── Saving ────────────────────────────────────────────────────

    @Test
    fun `saving stores what the form shows and closes it`() = runTest(dispatcher) {
        val viewModel = analysedViewModel()
        viewModel.onIntent(CreateLinkIntent.ToggleTag("Kitchen"))
        viewModel.onIntent(CreateLinkIntent.SetImageHidden(true))

        viewModel.onIntent(CreateLinkIntent.Save(" $GRINDER ", " Pepper grinder "))

        assertEquals(CreateLinkEffect.Saved, viewModel.effects.first())
        assertEquals(
            LinkDraft(GRINDER, "Pepper grinder", listOf("Kitchen"), LinkImageSource.Known(IMAGE), imageHidden = true) to NOW,
            repository.saves.single(),
        )
        assertTrue("the link just saved isn't a duplicate", viewModel.state.value.duplicateOf(GRINDER) == null)
    }

    @Test
    fun `saving doesn't wait for the page`() = runTest(dispatcher) {
        val viewModel = viewModel()
        viewModel.onIntent(CreateLinkIntent.UrlChanged(GRINDER))

        viewModel.onIntent(CreateLinkIntent.Save(GRINDER, ""))

        assertEquals(CreateLinkEffect.Saved, viewModel.effects.first())
        assertEquals("looked up after saving", LinkImageSource.Unknown, repository.saves.single().first.image)
    }

    @Test
    fun `a double tap saves once`() = runTest(dispatcher) {
        val viewModel = analysedViewModel()

        viewModel.onIntent(CreateLinkIntent.Save(GRINDER, "Pepper grinder"))
        viewModel.onIntent(CreateLinkIntent.Save(GRINDER, "Pepper grinder"))
        runCurrent()

        assertEquals(1, repository.saves.size)
    }

    @Test
    fun `an address saved behind the live check doesn't close the form`() = runTest(dispatcher) {
        val viewModel = viewModel()
        // Saved elsewhere, and the form hasn't heard yet.
        repository.links.value = listOf(Link(1, GRINDER, "Pepper grinder", 0, thumbnailPath = null, tags = emptyList()))
        viewModel.onIntent(CreateLinkIntent.UrlChanged(GRINDER))

        viewModel.onIntent(CreateLinkIntent.Save(GRINDER, "Pepper grinder"))
        runCurrent()

        assertNull(withTimeoutOrNull(100) { viewModel.effects.first() })
        assertFalse(viewModel.state.value.saving)
        assertEquals(AlreadySavedLink(GRINDER, "Pepper grinder"), viewModel.state.value.duplicateOf(GRINDER))
    }

    private fun TestScope.viewModel(): CreateLinkViewModel {
        val viewModel = CreateLinkViewModel(
            observeTagNames = ObserveTagNames(repository),
            observeDuplicate = ObserveDuplicate(repository),
            analyzeLink = AnalyzeLink(pages, suggester, images),
            createTag = CreateTag(repository),
            retryLinkImage = RetryLinkImage(images),
            saveLink = SaveLink(repository, Clock { NOW }),
        )
        runCurrent()
        return viewModel
    }

    /** With [GRINDER] in the field and its page read. */
    private fun TestScope.analysedViewModel(): CreateLinkViewModel {
        val viewModel = viewModel()
        viewModel.onIntent(CreateLinkIntent.UrlChanged(GRINDER))
        advanceTimeBy(DEBOUNCE_MS + 1)
        runCurrent()
        assertEquals(PageReadPhase.Done, viewModel.state.value.phaseFor(GRINDER))
        return viewModel
    }

    private companion object {
        const val GRINDER = "https://curaahome.com/products/curaa-automatic-pepper-grinder"
        const val OTHER = "https://example.com/post"
        const val IMAGE = "https://cdn.curaahome.com/grinder.jpg"
        const val NOW = 1_700_000_000_000L

        /** CreateLinkViewModel's debounce. */
        const val DEBOUNCE_MS = 600L
    }
}
