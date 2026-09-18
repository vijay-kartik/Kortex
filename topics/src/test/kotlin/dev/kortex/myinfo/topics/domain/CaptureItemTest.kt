package dev.kortex.myinfo.topics.domain

import dev.kortex.myinfo.topics.domain.model.CaptureResult
import dev.kortex.myinfo.topics.domain.model.CaptureTarget
import dev.kortex.myinfo.topics.domain.model.ItemType
import dev.kortex.myinfo.topics.domain.model.NewItem
import dev.kortex.myinfo.topics.domain.model.TopicDraft
import dev.kortex.myinfo.topics.domain.port.Clock
import dev.kortex.myinfo.topics.domain.usecase.AddItem
import dev.kortex.myinfo.topics.domain.usecase.CaptureItem
import dev.kortex.myinfo.topics.domain.usecase.CreateTopic
import dev.kortex.myinfo.topics.domain.usecase.DetectItemType
import dev.kortex.myinfo.topics.domain.usecase.captureTypes
import dev.kortex.myinfo.topics.domain.usecase.defaultCaptureType
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureItemTest {
    private val repository = FakeTopicsRepository()
    private val detect = DetectItemType()
    private val clock = Clock { 0 }
    private val capture = CaptureItem(CreateTopic(repository, clock), AddItem(repository, clock), detect)

    @Test
    fun `addresses can be links, articles, videos or notes; text only notes`() {
        assertEquals(listOf(ItemType.Link, ItemType.Article, ItemType.Video, ItemType.Note), captureTypes(detect("gov.uk/visa")))
        assertEquals(listOf(ItemType.Note), captureTypes(detect("pack adapters")))
    }

    @Test
    fun `an address of a file starts as a link until files can be captured`() {
        assertEquals(ItemType.Link, defaultCaptureType(detect("example.com/visa.pdf")))
        assertEquals(ItemType.Video, defaultCaptureType(detect("youtu.be/abc")))
    }

    @Test
    fun `a video goes into the chosen topic with its title`() = runTest {
        val result = capture("youtube.com/watch?v=8xQ1n", ItemType.Video, "Dubai in 3 days", CaptureTarget.Existing(4))

        assertEquals(CaptureResult.Saved(4), result)
        assertEquals(NewItem.Link("https://youtube.com/watch?v=8xQ1n", "Dubai in 3 days", ItemType.Video), repository.items.single())
    }

    @Test
    fun `an address can be kept as a note`() = runTest {
        capture("gov.uk/check-sponsor", ItemType.Note, title = "ignored", CaptureTarget.Existing(4))

        assertEquals(NewItem.Note("gov.uk/check-sponsor"), repository.items.single())
    }

    @Test
    fun `a new topic is created with the item's section showing`() = runTest {
        val result = capture("Metro closes 00:30", ItemType.Note, null, CaptureTarget.New("Trip to Dubai"))

        assertEquals(CaptureResult.Saved(1), result)
        assertEquals(TopicDraft(name = "Trip to Dubai", sections = ItemType.DefaultSections + ItemType.Note), repository.topics.single())
    }

    @Test
    fun `nothing is created for an item that can't be saved`() = runTest {
        assertEquals(CaptureResult.Invalid, capture("   ", ItemType.Note, null, CaptureTarget.New("Trip")))
        assertEquals(CaptureResult.Invalid, capture("plain words", ItemType.Video, null, CaptureTarget.New("Trip")))
        assertTrue(repository.topics.isEmpty())
    }

    @Test
    fun `new topic names are checked`() = runTest {
        repository.createTopic(TopicDraft(name = "Trip"), nowMillis = 0)

        assertEquals(CaptureResult.NewTopicNameBlank, capture("note", ItemType.Note, null, CaptureTarget.New(" ")))
        assertEquals(CaptureResult.NewTopicNameTaken, capture("note", ItemType.Note, null, CaptureTarget.New("trip")))
    }

    @Test
    fun `a link the topic already holds is reported`() = runTest {
        capture("gov.uk/visa", ItemType.Link, null, CaptureTarget.Existing(4))

        assertEquals(CaptureResult.AlreadyInTopic, capture("https://gov.uk/visa", ItemType.Link, null, CaptureTarget.Existing(4)))
    }
}
