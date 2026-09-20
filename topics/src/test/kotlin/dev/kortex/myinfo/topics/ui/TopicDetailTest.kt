package dev.kortex.myinfo.topics.ui

import dev.kortex.myinfo.topics.domain.FakeEmailDirectory
import dev.kortex.myinfo.topics.domain.FakeTopicSummarizer
import dev.kortex.myinfo.topics.domain.FakeTopicsRepository
import dev.kortex.myinfo.topics.domain.model.ItemType
import dev.kortex.myinfo.topics.domain.model.Money
import dev.kortex.myinfo.topics.domain.model.SavedEmail
import dev.kortex.myinfo.topics.domain.model.SavedLink
import dev.kortex.myinfo.topics.domain.model.StoredFile
import dev.kortex.myinfo.topics.domain.model.SummaryDigest
import dev.kortex.myinfo.topics.domain.model.Topic
import dev.kortex.myinfo.topics.domain.model.TopicDetail
import dev.kortex.myinfo.topics.domain.model.TopicItem
import dev.kortex.myinfo.topics.domain.model.TopicSummary
import dev.kortex.myinfo.topics.domain.model.TopicViewMode
import dev.kortex.myinfo.topics.domain.port.Clock
import dev.kortex.myinfo.topics.domain.usecase.DeleteItems
import dev.kortex.myinfo.topics.domain.usecase.DeleteTopic
import dev.kortex.myinfo.topics.domain.usecase.RouteToEmail
import dev.kortex.myinfo.topics.domain.usecase.MoveItems
import dev.kortex.myinfo.topics.domain.usecase.ObserveTopic
import dev.kortex.myinfo.topics.domain.usecase.ObserveTopicSummary
import dev.kortex.myinfo.topics.domain.usecase.ObserveTopics
import dev.kortex.myinfo.topics.domain.usecase.SetItemDone
import dev.kortex.myinfo.topics.domain.usecase.SetItemsPinned
import dev.kortex.myinfo.topics.domain.usecase.SetTopicPinned
import dev.kortex.myinfo.topics.domain.usecase.SummarizeTopic
import dev.kortex.myinfo.topics.ui.common.TopicChoice
import dev.kortex.myinfo.topics.ui.detail.TopicDetailEffect
import dev.kortex.myinfo.topics.ui.detail.TopicDetailIntent
import dev.kortex.myinfo.topics.ui.detail.TopicDetailState
import dev.kortex.myinfo.topics.ui.detail.TopicDetailViewModel
import dev.kortex.myinfo.topics.ui.detail.TypeFilter
import dev.kortex.myinfo.topics.ui.detail.topicShareText
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
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

@OptIn(ExperimentalCoroutinesApi::class)
class TopicDetailTest {
    private val repository = FakeTopicsRepository()
    private val summarizer = FakeTopicSummarizer(reply = "You're planning 3 days in Dubai.")
    private val mailbox = FakeEmailDirectory()
    private val topic = Topic(7, "Trip to Dubai", purpose = "4 nights in March", pinned = false, sections = setOf(ItemType.Note, ItemType.Doc), createdAtMillis = 0, updatedAtMillis = 0)
    private val video = TopicItem.Video(1, 7, addedAtMillis = 30, link(1, "https://youtu.be/a", "Dubai in 3 days"), durationSeconds = null, watched = false)
    private val note = TopicItem.Note(2, 7, addedAtMillis = 20, text = "Metro closes 00:30")
    private val otherNote = TopicItem.Note(3, 7, addedAtMillis = 10, text = "Pack adapters")
    private val otherTopic = topic.copy(id = 8, name = "Job switch prep")

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `filters list chosen sections too, busiest first, empty last`() {
        val state = TopicDetailState(detail = TopicDetail(topic, listOf(video, note, otherNote)))

        assertEquals(
            listOf(TypeFilter(ItemType.Note, 2), TypeFilter(ItemType.Video, 1), TypeFilter(ItemType.Doc, 0)),
            state.filters,
        )
    }

    @Test
    fun `a filter narrows the feed, and falls back to everything once its section is gone`() {
        val state = TopicDetailState(detail = TopicDetail(topic, listOf(video, note)), filter = ItemType.Video)
        assertEquals(listOf(video), state.items)

        val gone = state.copy(filter = ItemType.Bill)
        assertNull(gone.activeFilter)
        assertEquals(listOf(video, note), gone.items)
    }

    @Test
    fun `share text lists items oldest first`() {
        val bill = TopicItem.Bill(4, 7, 5, "Hotel balance", Money(50_000, "AED"), issuedAtMillis = null, dueAtMillis = null, paid = false, file = null)

        assertEquals(
            "Trip to Dubai\n4 nights in March\n\n• Hotel balance — AED 500 (due)\n• Metro closes 00:30\n• Dubai in 3 days — https://youtu.be/a",
            topicShareText(TopicDetail(topic, listOf(video, note, bill))),
        )
    }

    @Test
    fun `tapping a link opens it`() = runTest {
        val viewModel = viewModel()

        viewModel.onIntent(TopicDetailIntent.OpenItem(video))

        assertEquals(TopicDetailEffect.OpenUrl("https://youtu.be/a"), viewModel.effects.first())
    }

    @Test
    fun `tapping a note copies its text, and long-press still starts selection`() = runTest {
        val viewModel = viewModel()

        viewModel.onIntent(TopicDetailIntent.OpenItem(note))
        assertEquals(TopicDetailEffect.CopyText("Metro closes 00:30"), viewModel.effects.first())

        viewModel.onIntent(TopicDetailIntent.StartSelection(note.id))
        assertEquals(setOf(note.id), viewModel.state.value.selection)
    }

    @Test
    fun `deleting closes the screen once`() = runTest {
        val viewModel = viewModel()

        viewModel.onIntent(TopicDetailIntent.AskDelete)
        assertEquals(true, viewModel.state.value.confirmingDelete)
        viewModel.onIntent(TopicDetailIntent.ConfirmDelete)

        assertEquals(listOf(7L), repository.deleted)
        assertEquals(TopicDetailEffect.Close, viewModel.effects.first())
        assertNull("the row disappearing mustn't close it again", withTimeoutOrNull(100) { viewModel.effects.first() })
    }

    @Test
    fun `a topic deleted elsewhere closes the screen`() = runTest {
        val viewModel = viewModel()

        repository.observedTopics.value = emptyList()

        assertEquals(TopicDetailEffect.Close, viewModel.effects.first())
    }

    @Test
    fun `tapping a doc hands its file out, a bill without an invoice has nothing to open`() = runTest {
        val doc = TopicItem.Doc(5, 7, 40, "Visa checklist", StoredFile("/files/topic-files/1-visa.pdf", "application/pdf"), pageCount = 4)
        val bill = TopicItem.Bill(6, 7, 35, "Hotel", Money(50_000, "AED"), issuedAtMillis = null, dueAtMillis = null, paid = false, file = null)
        val viewModel = viewModel()

        viewModel.onIntent(TopicDetailIntent.OpenItem(bill))
        viewModel.onIntent(TopicDetailIntent.OpenItem(doc))

        assertEquals(TopicDetailEffect.OpenFile(doc.file), viewModel.effects.first())
    }

    @Test
    fun `tapping an email offers the mail app a search for it, and the web as a fallback`() = runTest {
        val email = emailItem(rfc822MessageId = "abc@visa.example")
        val viewModel = viewModel()

        viewModel.onIntent(TopicDetailIntent.OpenItem(email))

        assertEquals(
            TopicDetailEffect.OpenEmail(
                appSearch = "rfc822msgid:abc@visa.example",
                web = "https://mail.example.com/#all/18c2a3f",
            ),
            viewModel.effects.first(),
        )
    }

    @Test
    fun `an email with no Message-ID still opens on the web`() = runTest {
        val viewModel = viewModel()

        viewModel.onIntent(TopicDetailIntent.OpenItem(emailItem(rfc822MessageId = null)))

        assertEquals(
            TopicDetailEffect.OpenEmail(appSearch = null, web = "https://mail.example.com/#all/18c2a3f"),
            viewModel.effects.first(),
        )
    }

    private fun emailItem(rfc822MessageId: String?) = TopicItem.Email(
        9, 7, addedAtMillis = 45,
        email = SavedEmail(
            messageId = "18c2a3f",
            threadId = null,
            subject = "Your visa appointment",
            from = "Visa Centre <noreply@visa.example>",
            snippet = "Confirmed for 14 March.",
            sentAtMillis = null,
            rfc822MessageId = rfc822MessageId,
            accountEmail = "me@example.com",
        ),
    )

    @Test
    fun `an article can be ticked off and back`() = runTest {
        val viewModel = viewModel()

        viewModel.onIntent(TopicDetailIntent.SetItemDone(video, done = true))
        assertEquals(mapOf(1L to true), repository.done)

        viewModel.onIntent(TopicDetailIntent.SetItemDone(video, done = false))
        assertEquals(mapOf(1L to false), repository.done)
    }

    // ── Selection (Figma: Topics 1e) ──────────────────────────────

    @Test
    fun `long-pressing picks an item out, and tapping another adds it`() = runTest {
        val viewModel = viewModel()

        viewModel.onIntent(TopicDetailIntent.StartSelection(video.id))
        assertTrue(viewModel.state.value.selecting)

        viewModel.onIntent(TopicDetailIntent.ToggleSelection(note.id))
        assertEquals(setOf(video.id, note.id), viewModel.state.value.selection)

        viewModel.onIntent(TopicDetailIntent.ToggleSelection(note.id))
        assertEquals(setOf(video.id), viewModel.state.value.selection)

        viewModel.onIntent(TopicDetailIntent.ClearSelection)
        assertFalse(viewModel.state.value.selecting)
    }

    @Test
    fun `a filter drops the selection rather than acting on what isn't on screen`() = runTest {
        val viewModel = viewModel()
        viewModel.onIntent(TopicDetailIntent.SelectAll)
        assertEquals(setOf(video.id, note.id), viewModel.state.value.selection)

        viewModel.onIntent(TopicDetailIntent.SelectFilter(ItemType.Video))

        assertFalse(viewModel.state.value.selecting)
    }

    @Test
    fun `an item deleted elsewhere leaves the selection on its own`() = runTest {
        val viewModel = viewModel()
        viewModel.onIntent(TopicDetailIntent.SelectAll)

        repository.observedItems.value = listOf(video)

        assertEquals(setOf(video.id), viewModel.state.value.selection)
    }

    @Test
    fun `pinning the selection pins them all, and pins again only once all are pinned`() = runTest {
        val viewModel = viewModel()
        viewModel.onIntent(TopicDetailIntent.SelectAll)
        // One of the two is pinned already, so the whole selection is not.
        repository.observedItems.value = listOf(video.copy(pinned = true), note)
        assertFalse(viewModel.state.value.selectionPinned)

        viewModel.onIntent(TopicDetailIntent.PinSelection)

        assertEquals(mapOf(video.id to true, note.id to true), repository.itemPins)
        assertFalse("acting on the selection leaves selection mode", viewModel.state.value.selecting)
    }

    @Test
    fun `unpinning happens when every picked item is already pinned`() = runTest {
        val viewModel = viewModel()
        repository.observedItems.value = listOf(video.copy(pinned = true), note.copy(pinned = true))
        viewModel.onIntent(TopicDetailIntent.SelectAll)
        assertTrue(viewModel.state.value.selectionPinned)

        viewModel.onIntent(TopicDetailIntent.PinSelection)

        assertEquals(mapOf(video.id to false, note.id to false), repository.itemPins)
    }

    @Test
    fun `moving the selection offers every other topic and reports where they went`() = runTest {
        repository.observedTopics.value = listOf(topic, otherTopic)
        val viewModel = viewModel()
        repository.observedTopics.value = listOf(topic, otherTopic)
        assertEquals(listOf(TopicChoice(8, "Job switch prep")), viewModel.state.value.moveTargets)

        viewModel.onIntent(TopicDetailIntent.SelectAll)
        assertTrue(viewModel.state.value.canMoveSelection)
        viewModel.onIntent(TopicDetailIntent.AskMoveSelection)
        assertTrue(viewModel.state.value.movingSelection)
        viewModel.onIntent(TopicDetailIntent.MoveSelectionTo(8))

        assertEquals(setOf(video.id, note.id) to 8L, repository.moved.single().let { it.first.toSet() to it.second })
        assertEquals(TopicDetailEffect.ShowMessage("2 items moved to Job switch prep"), viewModel.effects.first())
        assertFalse(viewModel.state.value.selecting)
    }

    @Test
    fun `with nowhere to move to, the move action is off`() = runTest {
        val viewModel = viewModel()
        viewModel.onIntent(TopicDetailIntent.SelectAll)

        assertEquals(emptyList<TopicChoice>(), viewModel.state.value.moveTargets)
        assertFalse(viewModel.state.value.canMoveSelection)
    }

    @Test
    fun `deleting the selection is confirmed first`() = runTest {
        val viewModel = viewModel()
        viewModel.onIntent(TopicDetailIntent.ToggleSelection(note.id))

        viewModel.onIntent(TopicDetailIntent.AskDeleteSelection)
        assertTrue(viewModel.state.value.confirmingSelectionDelete)
        viewModel.onIntent(TopicDetailIntent.CancelDeleteSelection)
        assertTrue("cancelling keeps the items picked out", viewModel.state.value.selecting)
        assertEquals(emptyList<Long>(), repository.deletedItems)

        viewModel.onIntent(TopicDetailIntent.AskDeleteSelection)
        viewModel.onIntent(TopicDetailIntent.ConfirmDeleteSelection)

        assertEquals(listOf(note.id), repository.deletedItems)
        assertEquals(TopicDetailEffect.ShowMessage("1 item deleted"), viewModel.effects.first())
    }

    @Test
    fun `the view mode is remembered and re-stamps the feed's clock`() = runTest {
        val viewModel = viewModel()
        assertEquals(TopicViewMode.Feed, viewModel.state.value.mode)

        viewModel.onIntent(TopicDetailIntent.SelectMode(TopicViewMode.Timeline))

        assertEquals(TopicViewMode.Timeline, viewModel.state.value.mode)
        assertEquals(NOW, viewModel.state.value.nowMillis)
    }

    // ── Agent summary (Figma: Topics 1b) ─────────────────────────

    @Test
    fun `a topic with items offers a first summary`() = runTest {
        val state = viewModel().state.value

        assertTrue(state.showSummaryCard)
        assertNull(state.summary)
        assertTrue(state.canSummarize)
        assertFalse(state.summaryStale)
    }

    @Test
    fun `an empty topic has nothing to summarise, so no card`() = runTest {
        val state = viewModel(items = emptyList()).state.value

        assertFalse(state.showSummaryCard)
        assertFalse(state.canSummarize)
    }

    @Test
    fun `summarising shows the agent's words, kept and current`() = runTest {
        val viewModel = viewModel()

        viewModel.onIntent(TopicDetailIntent.Summarize)

        val state = viewModel.state.value
        assertEquals("You're planning 3 days in Dubai.", state.summary?.text)
        assertEquals(NOW, state.summary?.generatedAtMillis)
        assertFalse(state.summarizing)
        assertFalse(state.summaryStale)
        assertEquals(state.summary, repository.summaries.value[7L])
    }

    @Test
    fun `a change to the topic marks the summary out of date without asking the agent again`() = runTest {
        val viewModel = viewModel()
        viewModel.onIntent(TopicDetailIntent.Summarize)

        repository.observedItems.value = listOf(video.copy(watched = true), note)

        assertTrue(viewModel.state.value.summaryStale)
        assertEquals("the model is only asked on request", 1, summarizer.read.size)

        viewModel.onIntent(TopicDetailIntent.Summarize)
        assertFalse("refreshing brings it up to date", viewModel.state.value.summaryStale)
        assertEquals(2, summarizer.read.size)
    }

    @Test
    fun `a summary kept from an earlier visit opens as current when nothing has changed`() = runTest {
        val fingerprint = SummaryDigest.of(TopicDetail(topic, listOf(video, note))).fingerprint
        repository.saveSummary(TopicSummary(7, "Written yesterday.", NOW - 86_400_000, fingerprint))

        val state = viewModel().state.value

        assertEquals("Written yesterday.", state.summary?.text)
        assertFalse(state.summaryStale)
        assertTrue("reading a kept summary costs no model call", summarizer.read.isEmpty())
    }

    @Test
    fun `a failed summary says why, and trying again clears it`() = runTest {
        val viewModel = viewModel()
        summarizer.failure = IOException("Ollama Cloud is selected but no API key is set. Add one in Settings.")

        viewModel.onIntent(TopicDetailIntent.Summarize)
        assertEquals("Ollama Cloud is selected but no API key is set. Add one in Settings.", viewModel.state.value.summaryError)
        assertFalse(viewModel.state.value.summarizing)
        assertTrue("the card still offers to try again", viewModel.state.value.canSummarize)

        summarizer.failure = null
        viewModel.onIntent(TopicDetailIntent.Summarize)
        assertNull(viewModel.state.value.summaryError)
        assertEquals("You're planning 3 days in Dubai.", viewModel.state.value.summary?.text)
    }

    private fun viewModel(items: List<TopicItem> = listOf(video, note)): TopicDetailViewModel {
        repository.observedTopics.value = repository.observedTopics.value.ifEmpty { listOf(topic) }
        repository.observedItems.value = items
        return TopicDetailViewModel(
            topicId = 7,
            observeTopic = ObserveTopic(repository),
            observeTopics = ObserveTopics(repository),
            clock = Clock { NOW },
            setTopicPinned = SetTopicPinned(repository),
            setItemDone = SetItemDone(repository, Clock { NOW }),
            routeToEmail = RouteToEmail(mailbox),
            setItemsPinned = SetItemsPinned(repository, Clock { NOW }),
            moveItems = MoveItems(repository, Clock { NOW }),
            deleteItems = DeleteItems(repository, Clock { NOW }),
            deleteTopic = DeleteTopic(repository),
            observeTopicSummary = ObserveTopicSummary(repository),
            summarizeTopic = SummarizeTopic(repository, summarizer, Clock { NOW }),
        )
    }

    private fun link(id: Long, url: String, title: String) = SavedLink(id, url, title, thumbnailPath = null)

    private companion object {
        const val NOW = 1_700_000_000_000L
    }
}
