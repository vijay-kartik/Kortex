package dev.kortex.app.data.topics

import android.util.Log
import dev.kortex.links.data.LinkWithTags
import dev.kortex.links.data.RoomLinksRepository
import dev.kortex.links.domain.model.Link
import dev.kortex.links.domain.model.LinkDraft
import dev.kortex.links.tagging.PageMetadata
import dev.kortex.links.tagging.PageMetadataFetcher
import dev.kortex.links.tagging.TagSuggester
import dev.kortex.myinfo.topics.domain.model.LinkLookup
import dev.kortex.myinfo.topics.domain.model.SavedLink
import dev.kortex.myinfo.topics.domain.port.LinkCatalog
import kotlinx.coroutines.CancellationException
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
    private val links: RoomLinksRepository,
    private val pages: PageMetadataFetcher,
    private val tagSuggester: TagSuggester,
) : LinkCatalog {
    // The page read for the last lookup, so saving right after it doesn't read the page again.
    @Volatile private var lastPage: PageMetadata? = null

    override fun observeLinks(): Flow<Map<Long, SavedLink>> =
        links.observeLinks().map { saved -> saved.associate { it.id to it.toSavedLink() } }

    override fun observeTagNames(): Flow<List<String>> = links.observeTagNames()

    override suspend fun findOrSave(url: String, title: String?, tags: List<String>?): Long {
        val existing = links.observeSavedLink(url).first()
        if (existing != null) {
            // Rewriting an unchanged set would still mark the link for sync, so only real edits go through.
            if (tags != null && !sameTags(tags, tagsOf(existing.id))) links.setLinkTags(existing.id, tags)
            return existing.id
        }
        val pageTitle = title ?: page(url).title
        // saveLink returns false if the address was saved between the check and here; either
        // way the link now exists. It only attaches tags that exist already, so the tags are set
        // afterwards, which creates any new ones.
        links.saveLink(LinkDraft(url = url, title = pageTitle ?: url, tags = emptyList()), System.currentTimeMillis())
        val id = checkNotNull(links.observeSavedLink(url).first()) { "Link for $url was not saved" }.id
        if (!tags.isNullOrEmpty()) links.setLinkTags(id, tags)
        return id
    }

    override suspend fun lookUp(url: String): LinkLookup {
        links.observeSavedLink(url).first()?.let { saved ->
            return LinkLookup(title = saved.title, inLinks = true, tags = tagsOf(saved.id))
        }
        val page = page(url)
        return LinkLookup(title = page.title, inLinks = false, tags = suggestTags(url, page))
    }

    private suspend fun page(url: String): PageMetadata =
        lastPage?.takeIf { it.url == url } ?: pages.fetch(url).also { lastPage = it }

    private suspend fun suggestTags(url: String, page: PageMetadata): List<String> = try {
        tagSuggester.suggest(page).map { it.tagName }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // Best-effort, as in the Links tab (e.g. model asset missing): the sheet works without them.
        Log.w(TAG, "Tag suggestion failed for $url", e)
        emptyList()
    }

    private suspend fun tagsOf(linkId: Long): List<String> =
        links.observeLinks().first().firstOrNull { it.id == linkId }?.tags.orEmpty()

    private fun sameTags(a: List<String>, b: List<String>): Boolean =
        a.map { it.trim().lowercase() }.toSet() == b.map { it.lowercase() }.toSet()

    private fun Link.toSavedLink() = SavedLink(
        id = id,
        url = url,
        title = title,
        thumbnailPath = thumbnailPath,
        tags = tags,
    )

    private companion object {
        const val TAG = "LinksLinkCatalog"
    }
}
