package dev.kortex.links.images

import dev.kortex.links.data.FakeLinkDao
import dev.kortex.links.data.LinkEntity
import dev.kortex.links.data.LinkImageNeeded
import dev.kortex.links.domain.FakeImageDownloads
import dev.kortex.links.domain.FakePageReader
import dev.kortex.links.domain.model.LinkImageSource
import dev.kortex.links.domain.model.LinkImageState
import dev.kortex.links.domain.model.PageMetadata
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LinkThumbnailAttacherTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val linkDao = FakeLinkDao()
    private val downloads = FakeImageDownloads()
    private val pages = FakePageReader()
    private val drafts by lazy { File(folder.root, "drafts") }
    private val images by lazy { File(folder.root, "images") }

    private fun attacher(scope: CoroutineScope) =
        LinkThumbnailAttacher(downloads, ThumbnailFiles(drafts, images), linkDao, pages, scope, now = { NOW })

    @Test
    fun `creating one touches neither disk nor database`() = runTest {
        linkDao.missingImages = listOf(LinkImageNeeded(1, PAGE, IMAGE))

        attacher(backgroundScope)
        runCurrent()

        assertFalse(images.exists())
        assertFalse(drafts.exists())
        assertTrue(downloads.retries.isEmpty())
    }

    @Test
    fun `a finished download is copied out to the link`() = runTest {
        val id = saveLink()
        attacher(backgroundScope).attachWhenReady(id, PAGE, LinkImageSource.Known(IMAGE))
        runCurrent()

        downloads.download(IMAGE).value = ready()
        runCurrent()

        val saved = images.listFiles().orEmpty().single()
        assertTrue(saved.name.startsWith("$id-"))
        assertEquals(saved.path, linkDao.rows.getValue(id).imagePath)
    }

    @Test
    fun `a link deleted mid-download is left no file`() = runTest {
        val id = saveLink()
        attacher(backgroundScope).attachWhenReady(id, PAGE, LinkImageSource.Known(IMAGE))
        runCurrent()

        linkDao.delete(id)
        downloads.download(IMAGE).value = ready()
        runCurrent()

        assertTrue(images.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `a page that names no image attaches nothing`() = runTest {
        val id = saveLink()
        pages.pages[PAGE] = PageMetadata(PAGE, title = "A post")

        attacher(backgroundScope).attachWhenReady(id, PAGE, LinkImageSource.Unknown)
        runCurrent()

        assertEquals(listOf(PAGE), pages.reads)
        assertTrue(linkDao.imageWrites.isEmpty())
        assertTrue(downloads.downloads.isEmpty())
        assertNull(linkDao.rows.getValue(id).imageUrl)
    }

    @Test
    fun `start sweeps old orphans and attaches missing thumbnails`() = runTest {
        val id = saveLink()
        linkDao.missingImages = listOf(LinkImageNeeded(id, PAGE, IMAGE))
        images.mkdirs()
        val live = File(images, "$id-live.jpg").apply { writeText("x"); setLastModified(NOW - 120_000) }
        val oldOrphan = File(images, "99-old.jpg").apply { writeText("x"); setLastModified(NOW - 120_000) }
        val newOrphan = File(images, "98-new.jpg").apply { writeText("x"); setLastModified(NOW) }

        attacher(backgroundScope).start()
        runCurrent()

        assertTrue(live.exists())
        assertFalse(oldOrphan.exists())
        assertTrue(newOrphan.exists())
        assertEquals(listOf(IMAGE), downloads.retries)
    }

    @Test
    fun `deleting a link's images leaves other links' files`() = runTest {
        images.mkdirs()
        val mine = File(images, "1-a.jpg").apply { writeText("x") }
        val other = File(images, "12-b.jpg").apply { writeText("x") }

        attacher(backgroundScope).deleteImages(1)

        assertFalse(mine.exists())
        assertTrue(other.exists())
    }

    private suspend fun saveLink(): Long = linkDao.insert(LinkEntity(url = PAGE, title = "A post", createdAtMillis = NOW))

    private fun ready(): LinkImageState.Ready {
        val draft = folder.newFile("draft.jpg").apply { writeText("thumbnail") }
        return LinkImageState.Ready(draft.path, 512, 256)
    }

    private companion object {
        const val PAGE = "https://example.com/post"
        const val IMAGE = "https://example.com/cover.jpg"
        const val NOW = 1_700_000_000_000L
    }
}
