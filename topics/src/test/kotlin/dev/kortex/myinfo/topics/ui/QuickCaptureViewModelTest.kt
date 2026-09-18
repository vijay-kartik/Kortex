package dev.kortex.myinfo.topics.ui

import dev.kortex.myinfo.topics.domain.FakeLinkCatalog
import dev.kortex.myinfo.topics.domain.FakeTopicsRepository
import dev.kortex.myinfo.topics.domain.model.ItemType
import dev.kortex.myinfo.topics.domain.model.LinkLookup
import dev.kortex.myinfo.topics.domain.model.Topic
import dev.kortex.myinfo.topics.domain.port.Clock
import dev.kortex.myinfo.topics.domain.usecase.AddItem
import dev.kortex.myinfo.topics.domain.usecase.CaptureItem
import dev.kortex.myinfo.topics.domain.usecase.CreateTopic
import dev.kortex.myinfo.topics.domain.usecase.DetectItemType
import dev.kortex.myinfo.topics.domain.usecase.LookUpLink
import dev.kortex.myinfo.topics.domain.usecase.ObserveTopics
import dev.kortex.myinfo.topics.ui.capture.CaptureError
import dev.kortex.myinfo.topics.ui.capture.QuickCaptureEffect
import dev.kortex.myinfo.topics.ui.capture.QuickCaptureIntent
import dev.kortex.myinfo.topics.ui.capture.QuickCaptureViewModel
import dev.kortex.myinfo.topics.ui.capture.TopicChoice
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class QuickCaptureViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val repository = FakeTopicsRepository()
    private val catalog = FakeLinkCatalog()
    private lateinit var viewModel: QuickCaptureViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        repository.observedTopics.value = listOf(topic(1, "Trip to Dubai", updatedAt = 10), topic(2, "Job switch prep", updatedAt = 20))
        val detect = DetectItemType()
        val clock = Clock { 0 }
        viewModel = QuickCaptureViewModel(
            initialTopicId = 1,
            observeTopics = ObserveTopics(repository),
            detectItemType = detect,
            lookUpLink = LookUpLink(catalog),
            captureItem = CaptureItem(CreateTopic(repository, clock), AddItem(repository, clock), detect),
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `topics come most recent first, with the opening topic selected`() {
        val state = viewModel.state.value

        assertEquals(listOf(TopicChoice(2, "Job switch prep"), TopicChoice(1, "Trip to Dubai")), state.topics)
        assertEquals(1L, state.selectedTopicId)
        assertFalse(state.canSave)
    }

    @Test
    fun `a pasted video address is detected, and a change sticks only while it suits`() {
        viewModel.onIntent(QuickCaptureIntent.TextChanged("youtube.com/watch?v=8xQ1n"))
        assertEquals(ItemType.Video, viewModel.state.value.type)
        assertTrue(viewModel.state.value.typeIsDetected)

        viewModel.onIntent(QuickCaptureIntent.ChooseType(ItemType.Article))
        assertEquals(ItemType.Article, viewModel.state.value.type)
        assertFalse(viewModel.state.value.typeIsDetected)

        viewModel.onIntent(QuickCaptureIntent.TextChanged("just words"))
        assertEquals(ItemType.Note, viewModel.state.value.type)
    }

    @Test
    fun `the address is looked up once typing pauses`() = runTest(dispatcher) {
        catalog.lookups["https://gov.uk/visa"] = LinkLookup(title = "Check if you need a visa", inLinks = true)

        viewModel.onIntent(QuickCaptureIntent.TextChanged("gov.uk/visa"))
        assertTrue(viewModel.state.value.lookingUp)
        assertNull(viewModel.state.value.lookup)

        advanceUntilIdle()
        assertEquals(LinkLookup("Check if you need a visa", inLinks = true), viewModel.state.value.lookup)
        assertFalse(viewModel.state.value.lookingUp)
    }

    @Test
    fun `saving reports the topic by name`() = runTest(dispatcher) {
        viewModel.onIntent(QuickCaptureIntent.TextChanged("Metro closes 00:30"))
        viewModel.onIntent(QuickCaptureIntent.SelectTopic(2))
        viewModel.onIntent(QuickCaptureIntent.Save(text = "Metro closes 00:30", title = "", newTopicName = ""))

        assertEquals(QuickCaptureEffect.Saved(2, "Job switch prep"), viewModel.effects.first())
    }

    @Test
    fun `saving into a new topic uses the typed name`() = runTest(dispatcher) {
        viewModel.onIntent(QuickCaptureIntent.TextChanged("Metro closes 00:30"))
        viewModel.onIntent(QuickCaptureIntent.StartNewTopic)
        viewModel.onIntent(QuickCaptureIntent.Save(text = "Metro closes 00:30", title = "", newTopicName = " Dubai notes "))

        assertEquals(QuickCaptureEffect.Saved(1, "Dubai notes"), viewModel.effects.first())
    }

    @Test
    fun `a duplicate link is reported until the text changes`() = runTest(dispatcher) {
        viewModel.onIntent(QuickCaptureIntent.TextChanged("gov.uk/visa"))
        viewModel.onIntent(QuickCaptureIntent.Save(text = "gov.uk/visa", title = "", newTopicName = ""))
        viewModel.effects.first()

        val again = QuickCaptureViewModel(1, ObserveTopics(repository), DetectItemType(), LookUpLink(catalog), captureItemFor(repository))
        again.onIntent(QuickCaptureIntent.TextChanged("gov.uk/visa"))
        again.onIntent(QuickCaptureIntent.Save(text = "gov.uk/visa", title = "", newTopicName = ""))
        assertEquals(CaptureError.AlreadyInTopic, again.state.value.error)

        again.onIntent(QuickCaptureIntent.TextChanged("gov.uk/visa/apply"))
        assertNull(again.state.value.error)
    }

    private fun captureItemFor(repository: FakeTopicsRepository): CaptureItem {
        val clock = Clock { 0 }
        return CaptureItem(CreateTopic(repository, clock), AddItem(repository, clock), DetectItemType())
    }

    private fun topic(id: Long, name: String, updatedAt: Long) =
        Topic(id, name, purpose = null, pinned = false, sections = ItemType.DefaultSections, createdAtMillis = 0, updatedAtMillis = updatedAt)
}
