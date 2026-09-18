package dev.kortex.myinfo.topics.ui

import dev.kortex.myinfo.topics.domain.FakeTopicsRepository
import dev.kortex.myinfo.topics.domain.model.ItemType
import dev.kortex.myinfo.topics.domain.model.Money
import dev.kortex.myinfo.topics.domain.model.SavedLink
import dev.kortex.myinfo.topics.domain.model.Topic
import dev.kortex.myinfo.topics.domain.model.TopicDetail
import dev.kortex.myinfo.topics.domain.model.TopicItem
import dev.kortex.myinfo.topics.domain.usecase.DeleteTopic
import dev.kortex.myinfo.topics.domain.usecase.ObserveTopic
import dev.kortex.myinfo.topics.domain.usecase.SetTopicPinned
import dev.kortex.myinfo.topics.ui.detail.TopicDetailEffect
import dev.kortex.myinfo.topics.ui.detail.TopicDetailIntent
import dev.kortex.myinfo.topics.ui.detail.TopicDetailState
import dev.kortex.myinfo.topics.ui.detail.TopicDetailViewModel
import dev.kortex.myinfo.topics.ui.detail.TypeFilter
import dev.kortex.myinfo.topics.ui.detail.topicShareText
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
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TopicDetailTest {
    private val repository = FakeTopicsRepository()
    private val topic = Topic(7, "Trip to Dubai", purpose = "4 nights in March", pinned = false, sections = setOf(ItemType.Note, ItemType.Doc), createdAtMillis = 0, updatedAtMillis = 0)
    private val video = TopicItem.Video(1, 7, addedAtMillis = 30, link(1, "https://youtu.be/a", "Dubai in 3 days"), durationSeconds = null, watched = false)
    private val note = TopicItem.Note(2, 7, addedAtMillis = 20, text = "Metro closes 00:30")
    private val otherNote = TopicItem.Note(3, 7, addedAtMillis = 10, text = "Pack adapters")

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
    fun `tapping a link opens it; tapping a note does nothing`() = runTest {
        val viewModel = viewModel()

        viewModel.onIntent(TopicDetailIntent.OpenItem(note))
        viewModel.onIntent(TopicDetailIntent.OpenItem(video))

        assertEquals(TopicDetailEffect.OpenUrl("https://youtu.be/a"), viewModel.effects.first())
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

    private fun viewModel(): TopicDetailViewModel {
        repository.observedTopics.value = listOf(topic)
        repository.observedItems.value = listOf(video, note)
        return TopicDetailViewModel(7, ObserveTopic(repository), SetTopicPinned(repository), DeleteTopic(repository))
    }

    private fun link(id: Long, url: String, title: String) = SavedLink(id, url, title, thumbnailPath = null)
}
