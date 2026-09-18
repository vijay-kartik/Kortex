package dev.kortex.myinfo.topics.domain

import dev.kortex.myinfo.topics.domain.model.AddItemResult
import dev.kortex.myinfo.topics.domain.model.ItemType
import dev.kortex.myinfo.topics.domain.model.NewItem
import dev.kortex.myinfo.topics.domain.model.TopicDraft
import dev.kortex.myinfo.topics.domain.model.TopicSaveResult
import dev.kortex.myinfo.topics.domain.port.Clock
import dev.kortex.myinfo.topics.domain.usecase.AddItem
import dev.kortex.myinfo.topics.domain.usecase.CreateTopic
import dev.kortex.myinfo.topics.domain.usecase.MoveItems
import dev.kortex.myinfo.topics.domain.usecase.UpdateTopic
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TopicUseCasesTest {
    private val repository = FakeTopicsRepository()
    private val clock = Clock { NOW }

    @Test
    fun `create trims the name and drops a blank purpose`() = runTest {
        val result = CreateTopic(repository, clock)(TopicDraft(name = "  Trip to Dubai ", purpose = "   "))

        assertEquals(TopicSaveResult.Saved(1), result)
        assertEquals(TopicDraft(name = "Trip to Dubai", purpose = null), repository.topics.single())
    }

    @Test
    fun `create rejects a blank or taken name`() = runTest {
        val create = CreateTopic(repository, clock)
        create(TopicDraft(name = "Trip to Dubai"))

        assertEquals(TopicSaveResult.BlankName, create(TopicDraft(name = "  ")))
        assertEquals(TopicSaveResult.NameTaken, create(TopicDraft(name = "trip to dubai")))
        assertEquals(1, repository.topics.size)
    }

    @Test
    fun `update reports a taken name`() = runTest {
        val create = CreateTopic(repository, clock)
        create(TopicDraft(name = "Trip to Dubai"))
        create(TopicDraft(name = "Job switch prep"))

        assertEquals(TopicSaveResult.NameTaken, UpdateTopic(repository, clock)(2, TopicDraft(name = "TRIP TO DUBAI")))
        assertEquals(TopicSaveResult.Saved(2), UpdateTopic(repository, clock)(2, TopicDraft(name = "Job hunt")))
    }

    @Test
    fun `add normalises a link address and trims its title`() = runTest {
        val result = AddItem(repository, clock)(topicId = 1, NewItem.Link("youtube.com/watch?v=8xQ1n", " Dubai in 3 days ", ItemType.Video))

        assertEquals(AddItemResult.Added(1), result)
        assertEquals(NewItem.Link("https://youtube.com/watch?v=8xQ1n", "Dubai in 3 days", ItemType.Video), repository.items.single())
    }

    @Test
    fun `add rejects blank notes and non-addresses`() = runTest {
        val add = AddItem(repository, clock)

        assertEquals(AddItemResult.Invalid, add(topicId = 1, NewItem.Note("  \n ")))
        assertEquals(AddItemResult.Invalid, add(topicId = 1, NewItem.Link("not an address")))
        assertTrue(repository.items.isEmpty())
    }

    @Test
    fun `add reports a link the topic already holds`() = runTest {
        val add = AddItem(repository, clock)
        add(topicId = 1, NewItem.Link("https://gov.uk/check-sponsor"))

        assertEquals(AddItemResult.AlreadyInTopic, add(topicId = 1, NewItem.Link("gov.uk/check-sponsor")))
    }

    @Test
    fun `moving nothing doesn't touch the repository`() = runTest {
        MoveItems(repository, clock)(emptyList(), toTopicId = 1)

        assertEquals(0, repository.moves)
    }

    private companion object {
        const val NOW = 1_000L
    }
}
