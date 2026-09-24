package dev.kortex.myinfo.topics.domain

import dev.kortex.myinfo.topics.domain.model.ItemType
import dev.kortex.myinfo.topics.domain.model.NewItem
import dev.kortex.myinfo.topics.domain.model.SavedLink
import dev.kortex.myinfo.topics.domain.model.TopicDraft
import dev.kortex.myinfo.topics.domain.model.TopicSaveResult
import dev.kortex.myinfo.topics.domain.model.TopicSuggestion
import dev.kortex.myinfo.topics.domain.port.Clock
import dev.kortex.myinfo.topics.domain.usecase.AcceptTopicSuggestion
import dev.kortex.myinfo.topics.domain.usecase.AddItem
import dev.kortex.myinfo.topics.domain.usecase.CreateTopic
import dev.kortex.myinfo.topics.domain.usecase.DetectItemType
import dev.kortex.myinfo.topics.domain.usecase.ObserveTopicSuggestions
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class TopicSuggestionsTest {
    private val repository = FakeTopicsRepository()
    private val detect = DetectItemType()
    private val suggestions = ObserveTopicSuggestions(repository, FakeLinkCatalog(), detect)

    @Test
    fun `a tag on enough links becomes a suggestion, capitalised`() {
        val links = tagged("dubai", 3) + tagged("jobs", 2)

        val result = suggestions.suggestTopics(links, topicNames = emptyList())

        assertEquals(listOf("Dubai"), result.map { it.name })
        assertEquals(3, result.single().links.size)
    }

    @Test
    fun `largest first, at most three`() {
        val links = tagged("a", 3) + tagged("b", 5) + tagged("c", 4) + tagged("d", 6)

        assertEquals(listOf("D", "B", "C"), suggestions.suggestTopics(links, emptyList()).map { it.name })
    }

    @Test
    fun `a tag that already names a topic is not suggested`() {
        val links = tagged("dubai", 3)

        assertEquals(emptyList<TopicSuggestion>(), suggestions.suggestTopics(links, topicNames = listOf("DUBAI")))
    }

    @Test
    fun `mostly videos makes a video suggestion`() {
        val videos = List(2) { link(100L + it, "https://youtu.be/v$it", "movies") }
        val links = videos + link(200, "https://example.com/list", "movies")

        assertEquals(ItemType.Video, suggestions.suggestTopics(links, emptyList()).single().kind)
        assertEquals(ItemType.Link, suggestions.suggestTopics(tagged("movies", 3), emptyList()).single().kind)
    }

    @Test
    fun `accepting creates the topic and adds its links, oldest first`() = runTest {
        val clock = Clock { 0 }
        val accept = AcceptTopicSuggestion(CreateTopic(repository, clock), AddItem(repository, clock), detect)
        val newest = link(2, "https://youtu.be/new", "dubai")
        val oldest = link(1, "https://example.com/old", "dubai")

        val result = accept(TopicSuggestion("Dubai", links = listOf(newest, oldest), kind = ItemType.Link))

        assertEquals(TopicSaveResult.Saved(1), result)
        assertEquals(TopicDraft(name = "Dubai"), repository.topics.single())
        assertEquals(
            listOf(
                NewItem.Link("https://example.com/old", "Link 1", ItemType.Link),
                NewItem.Link("https://youtu.be/new", "Link 2", ItemType.Video),
            ),
            repository.items,
        )
    }

    private fun tagged(tag: String, count: Int): List<SavedLink> =
        List(count) { link(id = tag.hashCode() * 100L + it, url = "https://example.com/$tag/$it", tag = tag) }

    private fun link(id: Long, url: String, tag: String) = SavedLink(id, url, "Link $id", thumbnailPath = null, tags = listOf(tag))
}
