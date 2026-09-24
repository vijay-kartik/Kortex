package dev.kortex.myinfo.topics.ui

import dev.kortex.myinfo.topics.domain.FakeLinkCatalog
import dev.kortex.myinfo.topics.domain.FakeTopicsRepository
import dev.kortex.myinfo.topics.domain.model.ItemType
import dev.kortex.myinfo.topics.domain.model.SavedLink
import dev.kortex.myinfo.topics.domain.model.Topic
import dev.kortex.myinfo.topics.domain.model.TopicSuggestion
import dev.kortex.myinfo.topics.domain.port.Clock
import dev.kortex.myinfo.topics.domain.usecase.AcceptTopicSuggestion
import dev.kortex.myinfo.topics.domain.usecase.AddItem
import dev.kortex.myinfo.topics.domain.usecase.CreateTopic
import dev.kortex.myinfo.topics.domain.usecase.DeleteTopic
import dev.kortex.myinfo.topics.domain.usecase.DetectItemType
import dev.kortex.myinfo.topics.domain.usecase.ObserveTopicSuggestions
import dev.kortex.myinfo.topics.domain.usecase.ObserveTopics
import dev.kortex.myinfo.topics.domain.usecase.SetTopicPinned
import dev.kortex.myinfo.topics.ui.list.PendingTopicDeletion
import dev.kortex.myinfo.topics.ui.list.TopicsListEffect
import dev.kortex.myinfo.topics.ui.list.TopicsListIntent
import dev.kortex.myinfo.topics.ui.list.TopicsListViewModel
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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The topics list's ViewModel on a controlled clock: the undo window is five seconds of virtual
 * time, so these tests can step to just before it closes and just after.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TopicsListViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val repository = FakeTopicsRepository()
    private val clock = Clock { NOW }

    private val dubai = topic(1, "Trip to Dubai")
    private val jobs = topic(2, "Job switch prep")

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        repository.observedTopics.value = listOf(dubai, jobs)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ── Deleting, with undo ───────────────────────────────────────

    @Test
    fun `a deleted topic waits out the undo window before it is really deleted`() = runTest(dispatcher) {
        val viewModel = loadedViewModel()

        viewModel.onIntent(TopicsListIntent.Delete(dubai.id))
        runCurrent()

        assertEquals(PendingTopicDeletion(dubai.id, startedAtMillis = NOW, deadlineMillis = NOW + UNDO_MS), viewModel.state.value.pendingDeletion)
        assertTrue("still in the list, as its undo row", viewModel.state.value.topics.any { it.topic.id == dubai.id })
        assertTrue("nothing deleted yet", repository.deleted.isEmpty())

        advanceTimeBy(UNDO_MS - 1)
        runCurrent()
        assertTrue("not a moment early", repository.deleted.isEmpty())

        advanceTimeBy(2)
        runCurrent()
        assertEquals(listOf(dubai.id), repository.deleted)
        assertNull(viewModel.state.value.pendingDeletion)
        assertFalse(viewModel.state.value.topics.any { it.topic.id == dubai.id })
    }

    @Test
    fun `undo inside the window keeps the topic`() = runTest(dispatcher) {
        val viewModel = loadedViewModel()
        viewModel.onIntent(TopicsListIntent.Delete(dubai.id))
        runCurrent()

        advanceTimeBy(UNDO_MS / 2)
        viewModel.onIntent(TopicsListIntent.UndoDelete)
        advanceTimeBy(UNDO_MS * 2)
        runCurrent()

        assertTrue("never deleted", repository.deleted.isEmpty())
        assertNull(viewModel.state.value.pendingDeletion)
        assertTrue(viewModel.state.value.topics.any { it.topic.id == dubai.id })
    }

    @Test
    fun `deleting a second topic ends the first one's window at once`() = runTest(dispatcher) {
        val viewModel = loadedViewModel()
        viewModel.onIntent(TopicsListIntent.Delete(dubai.id))
        runCurrent()

        viewModel.onIntent(TopicsListIntent.Delete(jobs.id))
        runCurrent()

        // Only one topic can be restorable: the first goes now, the second gets the window.
        assertEquals(listOf(dubai.id), repository.deleted)
        assertEquals(jobs.id, viewModel.state.value.pendingDeletion?.topicId)
    }

    @Test
    fun `a deleted topic doesn't flash back if the list re-emits before its row is gone`() = runTest(dispatcher) {
        val viewModel = loadedViewModel()
        viewModel.onIntent(TopicsListIntent.Delete(dubai.id))
        runCurrent()
        advanceTimeBy(UNDO_MS + 1)
        runCurrent()

        // A read that started before the delete landed still carries the old row.
        repository.observedTopics.value = listOf(dubai, jobs)
        runCurrent()

        assertEquals(listOf(jobs.id), viewModel.state.value.topics.map { it.topic.id })
    }

    @Test
    fun `deleting closes the options tray it was chosen from`() = runTest(dispatcher) {
        val viewModel = loadedViewModel()
        viewModel.onIntent(TopicsListIntent.ShowOptions(dubai.id))
        assertEquals(dubai.id, viewModel.state.value.openOptionsTopicId)

        viewModel.onIntent(TopicsListIntent.Delete(dubai.id))

        assertNull(viewModel.state.value.openOptionsTopicId)
    }

    // ── Suggestions ───────────────────────────────────────────────

    @Test
    fun `accepting a suggestion creates the topic with its links and opens it`() = runTest(dispatcher) {
        val viewModel = loadedViewModel()
        val suggestion = TopicSuggestion(
            name = "Visas",
            links = listOf(
                SavedLink(1, "https://gov.uk/visa", "Check if you need a visa", thumbnailPath = null),
                SavedLink(2, "https://gov.uk/eta", "Get an ETA", thumbnailPath = null),
            ),
            kind = ItemType.Link,
        )

        viewModel.onIntent(TopicsListIntent.AcceptSuggestion(suggestion))

        assertEquals(TopicsListEffect.OpenTopic(1), viewModel.effects.first())
        assertEquals("Visas", repository.topics.single().name)
        assertEquals(2, repository.items.size)
    }

    @Test
    fun `a second tap on a suggestion while the first is saving creates it only once`() = runTest(dispatcher) {
        val viewModel = loadedViewModel()
        val suggestion = TopicSuggestion("Visas", listOf(SavedLink(1, "https://gov.uk/visa", "Visa", thumbnailPath = null)), ItemType.Link)

        viewModel.onIntent(TopicsListIntent.AcceptSuggestion(suggestion))
        viewModel.onIntent(TopicsListIntent.AcceptSuggestion(suggestion))
        runCurrent()

        assertEquals(1, repository.topics.size)
    }

    // ── Navigation ────────────────────────────────────────────────

    @Test
    fun `opening a topic closes any tray and leaves for it`() = runTest(dispatcher) {
        val viewModel = loadedViewModel()
        viewModel.onIntent(TopicsListIntent.ShowOptions(jobs.id))

        viewModel.onIntent(TopicsListIntent.OpenTopic(jobs.id))

        assertNull(viewModel.state.value.openOptionsTopicId)
        assertEquals(TopicsListEffect.OpenTopic(jobs.id), viewModel.effects.first())
    }

    @Test
    fun `the add button hands off to the host`() = runTest(dispatcher) {
        val viewModel = loadedViewModel()

        viewModel.onIntent(TopicsListIntent.CreateTopic)
        assertEquals(TopicsListEffect.OpenNewTopic, viewModel.effects.first())
    }

    /** Built and given its first read, so the list isn't still loading. */
    private fun TestScope.loadedViewModel(): TopicsListViewModel {
        val detect = DetectItemType()
        val viewModel = TopicsListViewModel(
            observeTopics = ObserveTopics(repository),
            observeTopicSuggestions = ObserveTopicSuggestions(repository, FakeLinkCatalog(), detect),
            setTopicPinned = SetTopicPinned(repository),
            deleteTopic = DeleteTopic(repository),
            acceptTopicSuggestion = AcceptTopicSuggestion(CreateTopic(repository, clock), AddItem(repository, clock), detect),
            clock = clock,
        )
        runCurrent()
        assertFalse("loaded", viewModel.state.value.loading)
        return viewModel
    }

    private fun topic(id: Long, name: String) =
        Topic(id, name, purpose = null, pinned = false, sections = emptySet(), createdAtMillis = 0, updatedAtMillis = 0)

    private companion object {
        const val NOW = 1_700_000_000_000L

        /** TopicsListViewModel's undo window. */
        const val UNDO_MS = 5_000L
    }
}
