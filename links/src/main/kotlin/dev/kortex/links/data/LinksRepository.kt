package dev.kortex.links.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LinksRepository @Inject constructor(
    private val linkDao: LinkDao,
    private val tagDao: TagDao,
) {
    fun observeTagNames(): Flow<List<String>> = tagDao.observeAll().map { tags -> tags.map { it.name } }

    /** Newest first. */
    fun observeLinks(): Flow<List<LinkWithTags>> = linkDao.observeLinksWithTags()

    /** Every tag, including unused ones (count 0), ordered by name. */
    fun observeTagLinkCounts(): Flow<List<TagLinkCount>> = tagDao.observeTagLinkCounts()

    /** No-op if a tag with this name already exists (names are case-insensitive). */
    suspend fun createTag(name: String) {
        tagDao.insert(TagEntity(name = name.trim()))
    }

    suspend fun saveLink(url: String, title: String, tagNames: List<String>) {
        val tagIds = if (tagNames.isEmpty()) emptyList() else tagDao.getByNames(tagNames).map { it.id }
        linkDao.insertWithTags(
            LinkEntity(url = url, title = title, createdAtMillis = System.currentTimeMillis()),
            tagIds,
        )
    }
}
