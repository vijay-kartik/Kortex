package dev.kortex.myinfo.topics.ui

import dev.kortex.myinfo.topics.domain.FakeTopicsRepository
import dev.kortex.myinfo.topics.domain.model.ItemType
import dev.kortex.myinfo.topics.domain.model.TopicDraft
import dev.kortex.myinfo.topics.domain.port.Clock
import dev.kortex.myinfo.topics.domain.usecase.CreateTopic
import dev.kortex.myinfo.topics.ui.create.NameError
import dev.kortex.myinfo.topics.ui.create.NewTopicEffect
import dev.kortex.myinfo.topics.ui.create.NewTopicIntent
import dev.kortex.myinfo.topics.ui.create.NewTopicViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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
class NewTopicViewModelTest {
    private val repository = FakeTopicsRepository()
    private lateinit var viewModel: NewTopicViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        viewModel = NewTopicViewModel(CreateTopic(repository, Clock { 0 }))
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `saves the chosen sections and pin`() = runTest {
        viewModel.onIntent(NewTopicIntent.ToggleSection(ItemType.Image))
        viewModel.onIntent(NewTopicIntent.ToggleSection(ItemType.Bill))
        viewModel.onIntent(NewTopicIntent.SetPinned(true))
        viewModel.onIntent(NewTopicIntent.Submit(name = " An incident to remember ", purpose = "For the insurance claim"))

        assertEquals(NewTopicEffect.Created(1), viewModel.effects.first())
        assertEquals(
            TopicDraft(
                name = "An incident to remember",
                purpose = "For the insurance claim",
                sections = setOf(ItemType.Note, ItemType.Doc, ItemType.Bill),
                pinned = true,
            ),
            repository.topics.single(),
        )
        assertTrue("a second tap mustn't save twice", viewModel.state.value.saving)
    }

    @Test
    fun `a blank name is rejected until edited`() = runTest {
        viewModel.onIntent(NewTopicIntent.Submit(name = "   ", purpose = ""))

        assertEquals(NameError.Blank, viewModel.state.value.nameError)
        assertFalse(viewModel.state.value.saving)

        viewModel.onIntent(NewTopicIntent.NameEdited)
        assertEquals(null, viewModel.state.value.nameError)
    }

    @Test
    fun `a taken name is rejected`() = runTest {
        repository.createTopic(TopicDraft(name = "Trip to Dubai"), nowMillis = 0)

        viewModel.onIntent(NewTopicIntent.Submit(name = "trip to dubai", purpose = ""))

        assertEquals(NameError.Taken, viewModel.state.value.nameError)
        assertEquals(1, repository.topics.size)
    }
}
