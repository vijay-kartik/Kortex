package dev.kortex.sync.links

import dev.kortex.links.data.LinkEntity
import dev.kortex.links.data.LinkIdUid
import dev.kortex.links.data.LinkImageNeeded
import dev.kortex.links.data.LinkPullResult
import dev.kortex.links.data.LinkSyncDao
import dev.kortex.links.data.LinkTagCrossRef
import dev.kortex.links.data.LinkWithTags
import dev.kortex.links.data.RemoteLink
import dev.kortex.links.data.SyncTombstoneEntity
import dev.kortex.links.data.TagEntity
import dev.kortex.links.domain.model.LinkImageSource
import dev.kortex.links.domain.port.LinkThumbnails
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalLinksTest {
    private val dao = FakeLinkSyncDao()
    private val thumbnails = RecordingThumbnails()
    private val local = LocalLinks(dao, thumbnails)

    @Test
    fun `pulled links that need an image have it attached, and deleted ones lose theirs`() = runTest {
        dao.pullResult = LinkPullResult(
            needsImage = listOf(LinkImageNeeded(1, "https://example.com/a", "https://example.com/a.jpg")),
            deleted = listOf(2, 3),
        )
        val pulled = listOf(remoteLink("a"))

        local.apply(pulled)

        assertEquals(listOf(pulled), dao.applied)
        assertEquals(
            listOf(Triple(1L, "https://example.com/a", LinkImageSource.Known("https://example.com/a.jpg"))),
            thumbnails.attached,
        )
        assertEquals(listOf(2L, 3L), thumbnails.deleted)
    }

    @Test
    fun `a pull that changed no images touches no thumbnails`() = runTest {
        local.apply(listOf(remoteLink("a")))

        assertTrue(thumbnails.attached.isEmpty())
        assertTrue(thumbnails.deleted.isEmpty())
    }

    @Test
    fun `discarding every link deletes every link's thumbnails`() = runTest {
        dao.discarded = listOf(4, 5)

        local.discardAll()

        assertEquals(listOf(4L, 5L), thumbnails.deleted)
    }

    private fun remoteLink(uid: String) = RemoteLink(
        uid = uid,
        url = "https://example.com/$uid",
        title = uid,
        tags = emptyList(),
        imageUrl = null,
        imageHidden = false,
        createdAtMillis = 1_000,
        updatedAtMillis = 1_000,
        deleted = false,
    )

    private class RecordingThumbnails : LinkThumbnails {
        val attached = mutableListOf<Triple<Long, String, LinkImageSource>>()
        val deleted = mutableListOf<Long>()

        override fun start() = Unit

        override fun attachWhenReady(linkId: Long, pageUrl: String, source: LinkImageSource) {
            attached += Triple(linkId, pageUrl, source)
        }

        override fun attachMissing() = Unit

        override suspend fun deleteImages(linkId: Long) {
            deleted += linkId
        }
    }

    /** Answers [applyPulled] with [pullResult] and [discardAll] with [discarded]; nothing else is used. */
    private class FakeLinkSyncDao : LinkSyncDao() {
        var pullResult = LinkPullResult(needsImage = emptyList(), deleted = emptyList())
        var discarded = emptyList<Long>()
        val applied = mutableListOf<List<RemoteLink>>()

        override suspend fun applyPulled(links: List<RemoteLink>): LinkPullResult {
            applied += links
            return pullResult
        }

        override suspend fun discardAll(): List<Long> = discarded

        override fun observeLinksWithTags(): Flow<List<LinkWithTags>> = unused()
        override suspend fun insert(link: LinkEntity): Long = unused()
        override suspend fun updateImage(id: Long, imageUrl: String?, imagePath: String?): Int = unused()
        override suspend fun linksMissingImage(): List<LinkImageNeeded> = unused()
        override suspend fun updateTitle(id: Long, title: String) = unused()
        override suspend fun delete(id: Long) = unused()
        override suspend fun getTagIds(linkId: Long): List<Long> = unused()
        override suspend fun deleteUnusedTags(tagIds: List<Long>) = unused()
        override suspend fun getAllIds(): List<Long> = unused()
        override suspend fun exists(id: Long): Boolean = unused()
        override suspend fun insertTags(tags: List<TagEntity>) = unused()
        override suspend fun getTagIdsByNames(names: List<String>): List<Long> = unused()
        override suspend fun deleteTagRefs(linkId: Long, tagIds: List<Long>) = unused()
        override suspend fun insertTagRefs(refs: List<LinkTagCrossRef>) = unused()
        override fun observeWithTagsByUrlKey(urlKey: String): Flow<LinkWithTags?> = unused()
        override suspend fun dirtyLinks(): List<LinkWithTags> = unused()
        override suspend fun tombstones(): List<SyncTombstoneEntity> = unused()
        override suspend fun markPushed(uid: String, dirty: Int) = unused()
        override suspend fun deleteTombstone(kind: String, uid: String, deletedAtMillis: Long) = unused()
        override suspend fun uidsOf(ids: Collection<Long>): List<LinkIdUid> = unused()
        override suspend fun idsOf(uids: Collection<String>): List<LinkIdUid> = unused()
        override suspend fun linkCount(): Int = unused()
        override suspend fun unpushedCount(): Int = unused()
        override fun observeUnpushedCount(): Flow<Int> = unused()
        override suspend fun markAllDirty() = unused()
        override suspend fun deleteTombstones(kind: String) = unused()
        override suspend fun deleteAllLinks() = unused()
        override suspend fun deleteAllTags() = unused()
        override suspend fun setApplying(applying: Boolean) = unused()
        override suspend fun findByUid(uid: String): LinkEntity? = unused()
        override suspend fun findByUrlKey(urlKey: String): LinkEntity? = unused()
        override suspend fun findTombstone(kind: String, uid: String): SyncTombstoneEntity? = unused()
        override suspend fun insertPulled(link: LinkEntity): Long = unused()
        override suspend fun updatePulled(link: LinkEntity) = unused()

        private fun unused(): Nothing = throw UnsupportedOperationException("Not faked")
    }
}
