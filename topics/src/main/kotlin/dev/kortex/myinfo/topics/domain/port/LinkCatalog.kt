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

    /** Every tag in Links, by name; re-emits as tags are created or deleted. */
    fun observeTagNames(): Flow<List<String>>

    /**
     * The id of the saved link for [url]'s address. When the address isn't saved yet it is saved
     * now, titled [title] or, without one, the page's own title (the address if it has none).
     * Non-null [tags] become the link's whole tag set, creating any tag that doesn't exist yet;
     * null leaves a saved link's tags alone.
     */
    suspend fun findOrSave(url: String, title: String?, tags: List<String>?): Long

    /** What is known about [url] before saving it; may read the page, so it can take a few seconds. */
    suspend fun lookUp(url: String): LinkLookup
}
