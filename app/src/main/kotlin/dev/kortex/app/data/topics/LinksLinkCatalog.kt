package dev.kortex.app.data.topics

import dev.kortex.links.data.LinkWithTags
import dev.kortex.links.data.LinksRepository
import dev.kortex.links.tagging.PageMetadata
import dev.kortex.links.tagging.PageMetadataFetcher
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
 * looked up after saving.
 */
class LinksLinkCatalog(
    private val links: LinksRepository,
    private val pages: PageMetadataFetcher,
) : LinkCatalog {
    // The page read for the last lookup, so saving right after it doesn't read the page again.
    @Volatile private var lastPage: PageMetadata? = null

    override fun observeLinks(): Flow<Map<Long, SavedLink>> =
        links.observeLinks().map { saved -> saved.associate { it.link.id to it.toSavedLink() } }

    override suspend fun findOrSave(url: String, title: String?): Long {
        links.observeSavedLink(url).first()?.let { return it.id }
        val pageTitle = title ?: page(url).title
        // saveLink returns false if the address was saved between the check and here; either
        // way the link now exists.
        links.saveLink(url = url, title = pageTitle ?: url, tagNames = emptyList())
        return checkNotNull(links.observeSavedLink(url).first()) { "Link for $url was not saved" }.id
    }

    override suspend fun lookUp(url: String): LinkLookup {
        links.observeSavedLink(url).first()?.let { return LinkLookup(title = it.title, inLinks = true) }
        return LinkLookup(title = page(url).title, inLinks = false)
    }

    private suspend fun page(url: String): PageMetadata =
        lastPage?.takeIf { it.url == url } ?: pages.fetch(url).also { lastPage = it }

    private fun LinkWithTags.toSavedLink() = SavedLink(
        id = link.id,
        url = link.url,
        title = link.title,
        thumbnailPath = link.imagePath.takeUnless { link.imageHidden },
        tags = tagNames,
    )
}
