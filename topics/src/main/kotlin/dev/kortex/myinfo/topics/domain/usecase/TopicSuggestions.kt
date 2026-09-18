package dev.kortex.myinfo.topics.domain.usecase

import dev.kortex.myinfo.topics.domain.model.ItemType
import dev.kortex.myinfo.topics.domain.model.NewItem
import dev.kortex.myinfo.topics.domain.model.SavedLink
import dev.kortex.myinfo.topics.domain.model.TopicDraft
import dev.kortex.myinfo.topics.domain.model.TopicSaveResult
import dev.kortex.myinfo.topics.domain.model.TopicSuggestion
import dev.kortex.myinfo.topics.domain.port.LinkCatalog
import dev.kortex.myinfo.topics.domain.repository.TopicsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * Suggested topics: each Links tag carried by at least [MinLinks] saved links, largest first, at
 * most [MaxSuggestions]. A tag that already names a topic (ignoring case) isn't suggested again.
 */
class ObserveTopicSuggestions(
    private val repository: TopicsRepository,
    private val linkCatalog: LinkCatalog,
    private val detectItemType: DetectItemType,
) {
    operator fun invoke(): Flow<List<TopicSuggestion>> =
        combine(linkCatalog.observeLinks(), repository.observeTopics()) { links, topics ->
            suggestTopics(links.values, topics.map { it.name })
        }

    internal fun suggestTopics(links: Collection<SavedLink>, topicNames: List<String>): List<TopicSuggestion> {
        val byTag = LinkedHashMap<String, MutableList<SavedLink>>()
        links.forEach { link -> link.tags.forEach { tag -> byTag.getOrPut(tag) { mutableListOf() } += link } }
        return byTag
            .filter { (tag, tagged) -> tagged.size >= MinLinks && topicNames.none { it.equals(tag, ignoreCase = true) } }
            .map { (tag, tagged) -> TopicSuggestion(tag.replaceFirstChar(Char::titlecase), tagged, kindOf(tagged)) }
            .sortedWith(compareByDescending<TopicSuggestion> { it.links.size }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })
            .take(MaxSuggestions)
    }

    private fun kindOf(links: List<SavedLink>): ItemType {
        val videos = links.count { detectItemType(it.url).type == ItemType.Video }
        return if (videos * 2 > links.size) ItemType.Video else ItemType.Link
    }

    companion object {
        const val MinLinks = 3
        const val MaxSuggestions = 3
    }
}

/**
 * Turns a suggestion into a topic holding its links, each as a video or a plain link by its
 * address. The links are already in the Links library, so none is saved twice.
 */
class AcceptTopicSuggestion(
    private val createTopic: CreateTopic,
    private val addItem: AddItem,
    private val detectItemType: DetectItemType,
) {
    suspend operator fun invoke(suggestion: TopicSuggestion): TopicSaveResult {
        val draft = TopicDraft(name = suggestion.name, sections = ItemType.DefaultSections + suggestion.kind)
        val result = createTopic(draft)
        if (result is TopicSaveResult.Saved) {
            // Oldest first, so the newest link ends up on top of the topic's feed.
            suggestion.links.reversed().forEach { link ->
                val type = detectItemType(link.url).type.takeIf { it.isLink } ?: ItemType.Link
                addItem(result.topicId, NewItem.Link(link.url, link.title, type))
            }
        }
        return result
    }
}
