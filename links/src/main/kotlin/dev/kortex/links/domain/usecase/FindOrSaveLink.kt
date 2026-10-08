package dev.kortex.links.domain.usecase

import dev.kortex.links.domain.model.Link
import dev.kortex.links.domain.model.LinkDraft
import dev.kortex.links.domain.port.Clock
import dev.kortex.links.domain.port.PageReader
import dev.kortex.links.domain.repository.LinksRepository
import kotlinx.coroutines.flow.first

sealed interface FindOrSaveResult {
    /** A new link, titled and tagged as asked. */
    data class Saved(val link: Link) : FindOrSaveResult
    /** The address was saved already (by linkUrlKey); it is left as it was. */
    data class AlreadySaved(val link: Link) : FindOrSaveResult
    /** Nothing was saved; the cause is logged. */
    data object Failed : FindOrSaveResult
}

/**
 * Finds the saved link for an address, or saves it: titled [title], else the page's own title,
 * else the address, and tagged with [tags], creating any new ones. Shared by Topics and the
 * agent's save_link tool.
 */
class FindOrSaveLink(
    private val repository: LinksRepository,
    private val pages: PageReader,
    private val clock: Clock,
) {
    suspend operator fun invoke(url: String, title: String?, tags: List<String>?): FindOrSaveResult {
        val address = url.trim()
        repository.observeSavedLink(address).first()?.let { return FindOrSaveResult.AlreadySaved(it) }
        val pageTitle = title?.trim()?.takeIf { it.isNotEmpty() } ?: pages.read(address).title
        // saveLink answers AlreadySaved if the address was saved between the check and here; either
        // way the link now exists. It only attaches tags that exist already, so the tags are set
        // afterwards, which creates any new ones.
        repository.saveLink(LinkDraft(url = address, title = pageTitle ?: address, tags = emptyList()), clock.nowMillis())
        val saved = repository.observeSavedLink(address).first() ?: return FindOrSaveResult.Failed
        if (tags.isNullOrEmpty()) return FindOrSaveResult.Saved(saved)
        repository.setLinkTags(saved.id, tags)
        return FindOrSaveResult.Saved(repository.observeSavedLink(address).first() ?: saved.copy(tags = tags))
    }
}
