package dev.kortex.links.ui.list

import dev.kortex.links.domain.FakeLinksRepository
import dev.kortex.links.domain.model.Link
import dev.kortex.links.domain.model.TagCount
import dev.kortex.links.domain.port.Clock
import dev.kortex.links.domain.usecase.DeleteLink
import dev.kortex.links.domain.usecase.ObserveLinks
import dev.kortex.links.domain.usecase.ObserveTagCounts
import dev.kortex.links.domain.usecase.SetLinkTags
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

/**
 * The links list's ViewModel on a controlled clock: the undo window is five seconds of virtual
 * time, so these tests can step to just before it closes and just after.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LinksViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val repository = FakeLinksRepository()
    private val clock = Clock { NOW }

    private val grinder = link(1, "Pepper grinder", "https://curaahome.com/grinder", "Kitchen", "Gifts")
    private val llama = link(2, "llama.cpp", "https://github.com/ggml/llama.cpp", "AI")

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        repository.links.value = listOf(grinder, llama)
        repository.tagCounts.value = listOf(TagCount("AI", 1), TagCount("Gifts", 1), TagCount("Kitchen", 1))
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ── Deleting, with undo ───────────────────────────────────────

    @Test
    fun `a deleted link waits out the undo window before it is really deleted`() = runTest(dispatcher) {
        val viewModel = loadedViewModel()

        viewModel.onIntent(LinksIntent.Delete(grinder.id))
        runCurrent()

        assertEquals(PendingLinkDeletion(grinder.id, startedAtMillis = NOW, deadlineMillis = NOW + UNDO_MS), viewModel.state.value.pendingDeletion)
        assertTrue("still in the list, as its undo row", viewModel.state.value.visibleLinks.any { it.id == grinder.id })
        assertTrue("nothing deleted yet", repository.deleted.isEmpty())

        advanceTimeBy(UNDO_MS - 1)
        runCurrent()
        assertTrue("not a moment early", repository.deleted.isEmpty())

        advanceTimeBy(2)
        runCurrent()
        assertEquals(listOf(grinder.id), repository.deleted)
        assertNull(viewModel.state.value.pendingDeletion)
        assertFalse(viewModel.state.value.links.any { it.id == grinder.id })
    }

    @Test
    fun `undo inside the window keeps the link`() = runTest(dispatcher) {
        val viewModel = loadedViewModel()
        viewModel.onIntent(LinksIntent.Delete(grinder.id))
        runCurrent()

        advanceTimeBy(UNDO_MS / 2)
        viewModel.onIntent(LinksIntent.UndoDelete)
        advanceTimeBy(UNDO_MS * 2)
        runCurrent()

        assertTrue("never deleted", repository.deleted.isEmpty())
        assertNull(viewModel.state.value.pendingDeletion)
        assertTrue(viewModel.state.value.links.any { it.id == grinder.id })
    }

    @Test
    fun `deleting a second link ends the first one's window at once`() = runTest(dispatcher) {
        val viewModel = loadedViewModel()
        viewModel.onIntent(LinksIntent.Delete(grinder.id))
        runCurrent()

        viewModel.onIntent(LinksIntent.Delete(llama.id))
        runCurrent()

        // Only one link can be restorable: the first goes now, the second gets the window.
        assertEquals(listOf(grinder.id), repository.deleted)
        assertEquals(llama.id, viewModel.state.value.pendingDeletion?.linkId)
    }

    @Test
    fun `a deleted link doesn't flash back if the list re-emits before its row is gone`() = runTest(dispatcher) {
        val viewModel = loadedViewModel()
        viewModel.onIntent(LinksIntent.Delete(grinder.id))
        runCurrent()
        advanceTimeBy(UNDO_MS + 1)
        runCurrent()

        // A read that started before the delete landed still carries the old row.
        repository.links.value = listOf(grinder, llama)
        runCurrent()

        assertEquals(listOf(llama.id), viewModel.state.value.links.map { it.id })
    }

    @Test
    fun `deleting closes the options tray it was chosen from`() = runTest(dispatcher) {
        val viewModel = loadedViewModel()
        viewModel.onIntent(LinksIntent.ShowOptions(grinder.id))
        assertEquals(grinder.id, viewModel.state.value.openOptionsLinkId)

        viewModel.onIntent(LinksIntent.Delete(grinder.id))

        assertNull(viewModel.state.value.openOptionsLinkId)
    }

    // ── Options tray ──────────────────────────────────────────────

    @Test
    fun `long-pressing the open card again keeps its draft`() = runTest(dispatcher) {
        val viewModel = loadedViewModel()
        viewModel.onIntent(LinksIntent.ShowOptions(grinder.id))
        viewModel.onIntent(LinksIntent.EditTags)
        viewModel.onIntent(LinksIntent.ToggleDraftTag("AI"))

        viewModel.onIntent(LinksIntent.ShowOptions(grinder.id))

        assertEquals(TagDraft(listOf("Kitchen", "Gifts", "AI")), viewModel.state.value.tagDraft)
    }

    @Test
    fun `open and share close the tray and leave for the link`() = runTest(dispatcher) {
        val viewModel = loadedViewModel()
        viewModel.onIntent(LinksIntent.ShowOptions(grinder.id))

        viewModel.onIntent(LinksIntent.Open(grinder.id))
        assertNull(viewModel.state.value.openOptionsLinkId)
        assertEquals(LinksEffect.OpenUrl(grinder.url), viewModel.effects.first())

        viewModel.onIntent(LinksIntent.ShowOptions(llama.id))
        viewModel.onIntent(LinksIntent.Share(llama.id))
        assertNull(viewModel.state.value.openOptionsLinkId)
        assertEquals(LinksEffect.Share(llama.url, llama.title), viewModel.effects.first())
    }

    @Test
    fun `tapping a card copies its address`() = runTest(dispatcher) {
        val viewModel = loadedViewModel()

        viewModel.onIntent(LinksIntent.Copy(llama.id))

        assertEquals(LinksEffect.CopyUrl(llama.url), viewModel.effects.first())
    }

    @Test
    fun `closing saves the draft only when it changed`() = runTest(dispatcher) {
        val viewModel = loadedViewModel()
        viewModel.onIntent(LinksIntent.ShowOptions(grinder.id))
        viewModel.onIntent(LinksIntent.EditTags)
        viewModel.onIntent(LinksIntent.CloseOptions())
        runCurrent()
        assertTrue("unchanged, so nothing written", repository.tagWrites.isEmpty())
        assertNull(viewModel.state.value.tagDraft)

        viewModel.onIntent(LinksIntent.ShowOptions(grinder.id))
        viewModel.onIntent(LinksIntent.EditTags)
        viewModel.onIntent(LinksIntent.ToggleDraftTag("Gifts"))
        viewModel.onIntent(LinksIntent.CloseOptions(typedTagName = " ai "))
        runCurrent()

        // The name left in the field is saved too, in its stored spelling.
        assertEquals(listOf(grinder.id to listOf("Kitchen", "AI")), repository.tagWrites)
        assertNull(viewModel.state.value.openOptionsLinkId)
    }

    @Test
    fun `adding a typed tag reuses its stored spelling and closes the field`() = runTest(dispatcher) {
        val viewModel = loadedViewModel()
        viewModel.onIntent(LinksIntent.ShowOptions(llama.id))
        viewModel.onIntent(LinksIntent.EditTags)
        viewModel.onIntent(LinksIntent.StartNewTag)
        assertTrue(viewModel.state.value.tagDraft!!.adding)

        viewModel.onIntent(LinksIntent.AddDraftTag("kitchen"))

        assertEquals(TagDraft(listOf("AI", "Kitchen"), adding = false), viewModel.state.value.tagDraft)
        assertTrue(repository.tagWrites.isEmpty())
    }

    @Test
    fun `filtering out the open card closes it for good`() = runTest(dispatcher) {
        val viewModel = loadedViewModel()
        viewModel.onIntent(LinksIntent.ShowOptions(grinder.id))
        viewModel.onIntent(LinksIntent.EditTags)

        viewModel.onIntent(LinksIntent.QueryChanged("llama"))
        assertNull(viewModel.state.value.optionsLinkId)
        assertNull(viewModel.state.value.tagDraft)

        viewModel.onIntent(LinksIntent.QueryChanged(""))
        assertNull("undoing the filter mustn't reopen it", viewModel.state.value.openOptionsLinkId)
    }

    @Test
    fun `a card whose link vanishes, e.g. by sync, closes its tray`() = runTest(dispatcher) {
        val viewModel = loadedViewModel()
        viewModel.onIntent(LinksIntent.ShowOptions(grinder.id))

        repository.links.value = listOf(llama)
        runCurrent()
        repository.links.value = listOf(grinder, llama)
        runCurrent()

        assertNull(viewModel.state.value.openOptionsLinkId)
    }

    @Test
    fun `tag filters toggle`() = runTest(dispatcher) {
        val viewModel = loadedViewModel()

        viewModel.onIntent(LinksIntent.ToggleTagFilter("AI"))
        assertEquals(listOf(llama), viewModel.state.value.visibleLinks)

        viewModel.onIntent(LinksIntent.ToggleTagFilter("AI"))
        assertEquals(listOf(grinder, llama), viewModel.state.value.visibleLinks)
    }

    // ── Navigation ────────────────────────────────────────────────

    @Test
    fun `the add button hands off to the host`() = runTest(dispatcher) {
        val viewModel = loadedViewModel()

        viewModel.onIntent(LinksIntent.CreateLink)

        assertEquals(LinksEffect.OpenCreateLink, viewModel.effects.first())
        assertNull("one effect per intent", withTimeoutOrNull(100) { viewModel.effects.first() })
    }

    /** Built and given its first read, so the list isn't still loading. */
    private fun TestScope.loadedViewModel(): LinksViewModel {
        val viewModel = LinksViewModel(
            observeLinks = ObserveLinks(repository),
            observeTagCounts = ObserveTagCounts(repository),
            setLinkTags = SetLinkTags(repository),
            deleteLink = DeleteLink(repository),
            clock = clock,
        )
        runCurrent()
        assertFalse("loaded", viewModel.state.value.loading)
        return viewModel
    }

    private fun link(id: Long, title: String, url: String, vararg tags: String) =
        Link(id, url, title, createdAtMillis = id, thumbnailPath = null, tags = tags.toList())

    private companion object {
        const val NOW = 1_700_000_000_000L

        /** LinksViewModel's undo window. */
        const val UNDO_MS = 5_000L
    }
}
