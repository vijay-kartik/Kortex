package dev.kortex.app.data.topics

import dev.kortex.links.domain.model.Link
import dev.kortex.links.domain.model.PageMetadata
import dev.kortex.links.domain.port.Clock
import dev.kortex.links.domain.port.PageReader
import dev.kortex.links.domain.port.TagSuggester
import dev.kortex.links.domain.repository.LinksRepository
import dev.kortex.links.domain.usecase.FindOrSaveLink
import dev.kortex.links.domain.usecase.FindOrSaveResult
import dev.kortex.myinfo.topics.domain.model.LinkLookup
import dev.kortex.myinfo.topics.domain.model.SavedLink
import dev.kortex.myinfo.topics.domain.port.LinkCatalog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Topics' view of the Links library. An address already in Links is reused (matched by
 * `linkUrlKey`, as the Links tab does), so a link saved into a topic shows in the Links tab
 * only when it wasn't there before. New links get their thumbnail the way the Links tab's do:
 * looked up after saving; and their suggested tags the way the Links tab's add form does.
 */
class LinksLinkCatalog(
    private val links: LinksRepository,
    private val pages: PageReader,
    private val tagSuggester: TagSuggester,
) : LinkCatalog {
    // The page read for the last lookup, so saving right after it doesn't read the page again.
    @Volatile private var lastPage: PageMetadata? = null
    private val findOrSaveLink = FindOrSaveLink(
        links,
        object : PageReader {
            override suspend fun read(url: String): PageMetadata = page(url)
        },
        Clock.System,
    )

    override fun observeLinks(): Flow<Map<Long, SavedLink>> =
        links.observeLinks().map { saved -> saved.associate { it.id to it.toSavedLink() } }

    override fun observeTagNames(): Flow<List<String>> = links.observeTagNames()

    override suspend fun findOrSave(url: String, title: String?, tags: List<String>?): Long =
        when (val result = findOrSaveLink(url, title, tags)) {
            is FindOrSaveResult.Saved -> result.link.id
            is FindOrSaveResult.AlreadySaved -> {
                val existing = result.link
                // Rewriting an unchanged set would still mark the link for sync, so only real edits go through.
                if (tags != null && !sameTags(tags, existing.tags)) links.setLinkTags(existing.id, tags)
                existing.id
            }
            FindOrSaveResult.Failed -> error("Link for $url was not saved")
        }

    override suspend fun lookUp(url: String): LinkLookup {
        links.observeSavedLink(url).first()?.let { saved ->
            return LinkLookup(title = saved.title, inLinks = true, tags = saved.tags)
        }
        val page = page(url)
        // Best-effort, as in the Links tab: the suggester answers nothing rather than failing.
        return LinkLookup(title = page.title, inLinks = false, tags = tagSuggester.suggest(page).map { it.tagName })
    }

    private suspend fun page(url: String): PageMetadata =
        lastPage?.takeIf { it.url == url } ?: pages.read(url).also { lastPage = it }

    private fun sameTags(a: List<String>, b: List<String>): Boolean =
        a.map { it.trim().lowercase() }.toSet() == b.map { it.lowercase() }.toSet()

    private fun Link.toSavedLink() = SavedLink(
        id = id,
        url = url,
        title = title,
        thumbnailPath = thumbnailPath,
        tags = tags,
    )
}
