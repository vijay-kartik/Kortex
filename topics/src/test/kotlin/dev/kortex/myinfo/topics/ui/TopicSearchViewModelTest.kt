package dev.kortex.myinfo.topics.ui

import dev.kortex.myinfo.topics.domain.FakeTopicsRepository
import dev.kortex.myinfo.topics.domain.model.ItemType
import dev.kortex.myinfo.topics.domain.model.SearchScope
import dev.kortex.myinfo.topics.domain.model.Topic
import dev.kortex.myinfo.topics.domain.model.TopicItem
import dev.kortex.myinfo.topics.domain.usecase.ObserveSearchCorpus
import dev.kortex.myinfo.topics.ui.search.TopicSearchEffect
import dev.kortex.myinfo.topics.ui.search.TopicSearchIntent
import dev.kortex.myinfo.topics.ui.search.TopicSearchViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TopicSearchViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val repository = FakeTopicsRepository()
    private lateinit var viewModel: TopicSearchViewModel

    private val dubai = topic(1, "Trip to Dubai")
    private val jobs = topic(2, "Job switch prep")
    private val metroNote = TopicItem.Note(1, 1, 50, "Metro red line closes 00:30")
    private val commuteNote = TopicItem.Note(2, 2, 40, "Ask about the metro commute")

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        repository.observedTopics.value = listOf(dubai, jobs)
        repository.observedItems.value = listOf(metroNote, commuteNote)
        viewModel = TopicSearchViewModel(ObserveSearchCorpus(repository))
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `nothing is searched until something is typed`() {
        val state = viewModel.state.value

        assertTrue(state.idle)
        assertFalse(state.searching)
        assertTrue(state.results.empty)
        assertFalse(state.noResults)
    }

    @Test
    fun `typing searches once the keystrokes stop`() = runTest(dispatcher) {
        viewModel.onIntent(TopicSearchIntent.QueryChanged("m"))
        viewModel.onIntent(TopicSearchIntent.QueryChanged("me"))
        viewModel.onIntent(TopicSearchIntent.QueryChanged("metro"))

        assertTrue("still catching up", viewModel.state.value.searching)
        assertTrue(viewModel.state.value.results.empty)

        advanceUntilIdle()

        val state = viewModel.state.value
        assertFalse(state.searching)
        assertEquals("metro", state.query)
        assertEquals(2, state.results.itemCount)
    }

    @Test
    fun `clearing the field goes straight back to the opening screen`() = runTest(dispatcher) {
        viewModel.onIntent(TopicSearchIntent.QueryChanged("metro"))
        advanceUntilIdle()
        assertFalse(viewModel.state.value.idle)

        viewModel.onIntent(TopicSearchIntent.QueryChanged(""))

        val state = viewModel.state.value
        assertTrue(state.idle)
        assertFalse(state.searching)
        assertTrue(state.results.empty)
    }

    @Test
    fun `a query that matches nothing says so rather than looking idle`() = runTest(dispatcher) {
        viewModel.onIntent(TopicSearchIntent.QueryChanged("helicopter"))
        advanceUntilIdle()

        val state = viewModel.state.value
        assertFalse(state.idle)
        assertTrue(state.noResults)
    }

    @Test
    fun `changing the scope re-searches at once, without waiting`() = runTest(dispatcher) {
        viewModel.onIntent(TopicSearchIntent.QueryChanged("metro"))
        advanceUntilIdle()

        viewModel.onIntent(TopicSearchIntent.SelectScope(SearchScope.Bills))

        // No advanceUntilIdle: a tap is answered on the spot.
        assertEquals(SearchScope.Bills, viewModel.state.value.scope)
        assertEquals(0, viewModel.state.value.results.itemCount)
        assertFalse(viewModel.state.value.searching)
    }

    @Test
    fun `an item saved while search is open shows up in the results`() = runTest(dispatcher) {
        viewModel.onIntent(TopicSearchIntent.QueryChanged("metro"))
        advanceUntilIdle()
        assertEquals(2, viewModel.state.value.results.itemCount)

        repository.observedItems.value = repository.observedItems.value + TopicItem.Note(3, 1, 60, "Metro day pass is 20 AED")

        assertEquals(3, viewModel.state.value.results.itemCount)
    }

    @Test
    fun `opening a result leaves for its topic`() = runTest(dispatcher) {
        viewModel.onIntent(TopicSearchIntent.OpenTopic(2))

        assertEquals(TopicSearchEffect.OpenTopic(2), viewModel.effects.first())
    }

    private fun topic(id: Long, name: String) =
        Topic(id, name, purpose = null, pinned = false, sections = ItemType.DefaultSections, createdAtMillis = 0, updatedAtMillis = 0)
}
