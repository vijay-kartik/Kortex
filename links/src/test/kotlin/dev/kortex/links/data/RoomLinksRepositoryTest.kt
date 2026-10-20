package dev.kortex.links.data

import android.database.sqlite.SQLiteConstraintException
import dev.kortex.links.domain.FakeLinkThumbnails
import dev.kortex.links.domain.model.LinkDraft
import dev.kortex.links.domain.model.LinkImageSource
import dev.kortex.links.domain.model.TagCount
import dev.kortex.links.domain.repository.SaveLinkResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomLinksRepositoryTest {
    private val linkDao = FakeLinkDao()
    private val thumbnails = FakeLinkThumbnails()
    private val repository = RoomLinksRepository(linkDao, NoTags, thumbnails)

    @Test
    fun `deleting a link deletes its thumbnails too`() = runTest {
        val id = linkDao.insert(LinkEntity(url = URL, title = "A post", createdAtMillis = NOW))

        repository.deleteLink(id)

        assertTrue(linkDao.rows.isEmpty())
        assertEquals(listOf(id), thumbnails.deleted)
    }

    @Test
    fun `a saved link has its thumbnail attached`() = runTest {
        val image = LinkImageSource.Known("https://example.com/cover.jpg")

        val result = repository.saveLink(LinkDraft(URL, "A post", emptyList(), image), NOW)

        assertEquals(SaveLinkResult.Saved(1), result)
        assertEquals(listOf(Triple(1L, URL, image)), thumbnails.attached)
    }

    @Test
    fun `an address already saved attaches nothing`() = runTest {
        linkDao.insertFailure = SQLiteConstraintException()

        val result = repository.saveLink(LinkDraft(URL, "A post", emptyList()), NOW)

        assertEquals(SaveLinkResult.AlreadySaved, result)
        assertTrue(thumbnails.attached.isEmpty())
    }

    @Test
    fun `a save that failed attaches nothing`() = runTest {
        linkDao.insertFailure = IllegalStateException("disk full")

        val result = repository.saveLink(LinkDraft(URL, "A post", emptyList()), NOW)

        assertEquals(SaveLinkResult.Failed, result)
        assertTrue(thumbnails.attached.isEmpty())
    }

    /** No tags exist; the drafts here carry none. */
    private object NoTags : TagDao {
        override fun observeAll(): Flow<List<TagEntity>> = throw UnsupportedOperationException()
        override suspend fun getAll(): List<TagEntity> = emptyList()
        override fun observeTagLinkCounts(): Flow<List<TagCount>> = throw UnsupportedOperationException()
        override suspend fun getByNames(names: List<String>): List<TagEntity> = emptyList()
        override suspend fun insert(tag: TagEntity): Long = throw UnsupportedOperationException()
        override suspend fun updateEmbedding(id: Long, embedding: ByteArray, model: String) = throw UnsupportedOperationException()
    }

    private companion object {
        const val URL = "https://example.com/post"
        const val NOW = 1_700_000_000_000L
    }
}
