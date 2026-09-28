package dev.kortex.links.domain

import dev.kortex.links.data.linkUrlKey
import dev.kortex.links.domain.model.Link
import dev.kortex.links.domain.model.LinkDraft
import dev.kortex.links.domain.model.LinkImageState
import dev.kortex.links.domain.model.PageMetadata
import dev.kortex.links.domain.model.TagCount
import dev.kortex.links.domain.port.ImageDownloads
import dev.kortex.links.domain.port.PageReader
import dev.kortex.links.domain.port.TagSuggester
import dev.kortex.links.domain.port.TagSuggestion
import dev.kortex.links.domain.repository.LinksRepository
import dev.kortex.links.domain.repository.SaveLinkResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/**
 * Links and tags in [links] and [tagCounts], which tests set directly; writes update them the way
 * Room would. Addresses match by [linkUrlKey], so a second save of one page is [SaveLinkResult.AlreadySaved].
 */
class FakeLinksRepository : LinksRepository {
    val links = MutableStateFlow<List<Link>>(emptyList())
    val tagCounts = MutableStateFlow<List<TagCount>>(emptyList())

    /** Every address [observeSavedLink] was asked about. */
    val savedLinkQueries = mutableListOf<String>()
    /** Every draft [saveLink] was handed, with the time it was given. */
    val saves = mutableListOf<Pair<LinkDraft, Long>>()
    val deleted = mutableListOf<Long>()
    private var nextId = 1L

    override fun observeLinks(): Flow<List<Link>> = links
    override fun observeTagCounts(): Flow<List<TagCount>> = tagCounts
    override fun observeTagNames(): Flow<List<String>> = tagCounts.map { tags -> tags.map { it.name } }

    override fun observeSavedLink(url: String): Flow<Link?> {
        savedLinkQueries += url
        return links.map { saved -> saved.firstOrNull { linkUrlKey(it.url) == linkUrlKey(url) } }
    }

    override suspend fun createTag(name: String) {
        if (tagCounts.value.none { it.name.equals(name, ignoreCase = true) }) {
            tagCounts.update { (it + TagCount(name, 0)).sortedBy { tag -> tag.name } }
        }
    }

    override suspend fun setLinkTags(linkId: Long, tagNames: List<String>) {
        tagNames.forEach { createTag(it) }
        links.update { saved -> saved.map { if (it.id == linkId) it.copy(tags = tagNames) else it } }
    }

    override suspend fun deleteLink(id: Long) {
        deleted += id
        links.update { saved -> saved.filterNot { it.id == id } }
    }

    override suspend fun saveLink(draft: LinkDraft, nowMillis: Long): SaveLinkResult {
        saves += draft to nowMillis
        if (links.value.any { linkUrlKey(it.url) == linkUrlKey(draft.url) }) return SaveLinkResult.AlreadySaved
        val link = Link(nextId++, draft.url, draft.title, nowMillis, thumbnailPath = null, tags = draft.tags)
        links.update { listOf(link) + it }
        return SaveLinkResult.Saved(link.id)
    }
}

/** Answers from [pages], or a page that couldn't be read. Reads of addresses in [gates] wait until the test completes them. */
class FakePageReader : PageReader {
    val pages = mutableMapOf<String, PageMetadata>()
    val gates = mutableMapOf<String, CompletableDeferred<Unit>>()
    val reads = mutableListOf<String>()

    override suspend fun read(url: String): PageMetadata {
        reads += url
        gates[url]?.await()
        return pages[url] ?: PageMetadata(url)
    }
}

/** Suggests [suggestions], best first; while [gate] is set, answers wait for it. */
class FakeTagSuggester(var suggestions: List<String> = emptyList()) : TagSuggester {
    var gate: CompletableDeferred<Unit>? = null

    override suspend fun suggest(page: PageMetadata): List<TagSuggestion> {
        gate?.await()
        return suggestions.mapIndexed { i, name -> TagSuggestion(name, score = 1f - i / 10f) }
    }
}

/** One download per address in [downloads], Loading until a test moves it on; [retries] records retries. */
class FakeImageDownloads : ImageDownloads {
    val downloads = mutableMapOf<String, MutableStateFlow<LinkImageState>>()
    val retries = mutableListOf<String>()

    override fun image(imageUrl: String): Flow<LinkImageState> = download(imageUrl)

    override fun retry(imageUrl: String) {
        retries += imageUrl
    }

    fun download(imageUrl: String): MutableStateFlow<LinkImageState> =
        downloads.getOrPut(imageUrl) { MutableStateFlow(LinkImageState.Loading(null)) }
}
