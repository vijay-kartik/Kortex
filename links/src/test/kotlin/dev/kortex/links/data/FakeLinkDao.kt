package dev.kortex.links.data

import kotlinx.coroutines.flow.Flow

/**
 * Links in [rows], with no tags. [insert] throws [insertFailure] when it's set, as Room would on the
 * unique address index. Queries no test here needs fail loudly.
 */
class FakeLinkDao : LinkDao() {
    val rows = mutableMapOf<Long, LinkEntity>()
    var insertFailure: Exception? = null
    var missingImages = emptyList<LinkImageNeeded>()
    /** Every [updateImage] call, in order: id, imageUrl, imagePath. */
    val imageWrites = mutableListOf<Triple<Long, String?, String?>>()
    private var nextId = 1L

    override suspend fun insert(link: LinkEntity): Long {
        insertFailure?.let { throw it }
        val id = nextId++
        rows[id] = link.copy(id = id)
        return id
    }

    override suspend fun updateImage(id: Long, imageUrl: String?, imagePath: String?): Int {
        imageWrites += Triple(id, imageUrl, imagePath)
        val row = rows[id] ?: return 0
        rows[id] = row.copy(imageUrl = imageUrl, imagePath = imagePath)
        return 1
    }

    override suspend fun linksMissingImage(): List<LinkImageNeeded> = missingImages

    override suspend fun delete(id: Long) {
        rows.remove(id)
    }

    override suspend fun getAllIds(): List<Long> = rows.keys.toList()

    override suspend fun exists(id: Long): Boolean = id in rows

    override suspend fun getTagIds(linkId: Long): List<Long> = emptyList()

    override suspend fun deleteUnusedTags(tagIds: List<Long>) = Unit

    override suspend fun insertTagRefs(refs: List<LinkTagCrossRef>) = Unit

    override fun observeLinksWithTags(): Flow<List<LinkWithTags>> = unused()

    override suspend fun updateTitle(id: Long, title: String) = unused()

    override suspend fun insertTags(tags: List<TagEntity>) = unused()

    override suspend fun getTagIdsByNames(names: List<String>): List<Long> = unused()

    override suspend fun deleteTagRefs(linkId: Long, tagIds: List<Long>) = unused()

    override fun observeWithTagsByUrlKey(urlKey: String): Flow<LinkWithTags?> = unused()

    private fun unused(): Nothing = throw UnsupportedOperationException("Not faked")
}
