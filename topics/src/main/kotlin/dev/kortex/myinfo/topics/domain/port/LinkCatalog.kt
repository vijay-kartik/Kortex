package dev.kortex.myinfo.topics.domain.port

import dev.kortex.myinfo.topics.domain.model.LinkLookup
import dev.kortex.myinfo.topics.domain.model.SavedLink
import kotlinx.coroutines.flow.Flow

/**
 * The user's Links library, as Topics sees it. Topics never copy a link: they point at one here,
 * so a link shows in the Links tab once however many topics hold it. Supplied by the host app.
 */
interface LinkCatalog {
    /** Every saved link by id; re-emits as links are saved, changed or deleted. Ids are never reused. */
    fun observeLinks(): Flow<Map<Long, SavedLink>>

    /**
     * The id of the saved link for [url]'s address. When the address isn't saved yet it is saved
     * now, titled [title] or, without one, the page's own title (the address if it has none).
     */
    suspend fun findOrSave(url: String, title: String?): Long

    /** What is known about [url] before saving it; may read the page, so it can take a few seconds. */
    suspend fun lookUp(url: String): LinkLookup
}
