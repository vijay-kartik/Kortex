package dev.kortex.links.domain.repository

import dev.kortex.links.domain.model.Link
import dev.kortex.links.domain.model.LinkDraft
import dev.kortex.links.domain.model.TagCount
import kotlinx.coroutines.flow.Flow

interface LinksRepository {
    /** Newest first. */
    fun observeLinks(): Flow<List<Link>>
    /** Every tag, unused ones too (count 0), by name. */
    fun observeTagCounts(): Flow<List<TagCount>>
    fun observeTagNames(): Flow<List<String>>
    /** The saved link [url] is an address of (by linkUrlKey), or null; follows saves as they happen. */
    fun observeSavedLink(url: String): Flow<Link?>
    /** No-op when the name exists, ignoring case. */
    suspend fun createTag(name: String)
    suspend fun setLinkTags(linkId: Long, tagNames: List<String>)
    suspend fun deleteLink(id: Long)
    suspend fun saveLink(draft: LinkDraft, nowMillis: Long): SaveLinkResult
}

sealed interface SaveLinkResult {
    data class Saved(val linkId: Long) : SaveLinkResult
    /** The address was saved between the form's live check and the insert. */
    data object AlreadySaved : SaveLinkResult
    /** Nothing was saved; the cause is logged. */
    data object Failed : SaveLinkResult
}