package dev.kortex.links.images

import android.util.Log
import dev.kortex.links.data.LinkDao
import dev.kortex.links.domain.model.LinkImageSource
import dev.kortex.links.domain.model.LinkImageState
import dev.kortex.links.domain.port.ImageDownloads
import dev.kortex.links.domain.port.LinkThumbnails
import dev.kortex.links.domain.port.PageReader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps each saved link's stored thumbnail in step with its row: copies a finished download out to
 * the link, and deletes the files of links that are gone. Downloading itself is [downloads]' job.
 */
@Singleton
class LinkThumbnailAttacher(
    private val downloads: ImageDownloads,
    private val files: ThumbnailFiles,
    private val linkDao: LinkDao,
    private val pages: PageReader,
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
) : LinkThumbnails {

    @Inject
    constructor(downloads: ImageDownloads, files: ThumbnailFiles, linkDao: LinkDao, pages: PageReader) :
        this(downloads, files, linkDao, pages, CoroutineScope(SupervisorJob() + Dispatchers.IO))

    /** Links with an [attachWhenReady] running, which [attachMissing] leaves to it. */
    private val attaching = ConcurrentHashMap.newKeySet<Long>()
    private val started = AtomicBoolean()

    override fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch { logFailure("Deleting orphaned images") { deleteOrphanedImages() } }
        attachMissing()
    }

    override fun attachWhenReady(linkId: Long, pageUrl: String, source: LinkImageSource) {
        attaching += linkId
        scope.launch {
            try {
                logFailure("Attaching the image of link $linkId") { attach(linkId, pageUrl, source) }
            } finally {
                attaching -= linkId
            }
        }
    }

    override fun attachMissing() {
        scope.launch {
            logFailure("Attaching missing images") {
                linkDao.linksMissingImage()
                    .filter { it.id !in attaching }
                    .forEach { attachWhenReady(it.id, it.url, LinkImageSource.Known(it.imageUrl)) }
            }
        }
    }

    override suspend fun deleteImages(linkId: Long) {
        withContext(Dispatchers.IO) { files.delete(linkId) }
    }

    private suspend fun attach(linkId: Long, pageUrl: String, source: LinkImageSource) {
        val imageUrl = when (source) {
            is LinkImageSource.Known -> source.imageUrl
            LinkImageSource.None -> return
            LinkImageSource.Unknown -> {
                val found = pages.read(pageUrl).imageUrl ?: return
                // Deleted while the page was being read: nothing to attach to.
                if (linkDao.updateImage(linkId, found, imagePath = null) == 0) return
                found
            }
        }
        downloads.retry(imageUrl)
        val ready = downloads.image(imageUrl).first { it !is LinkImageState.Loading } as? LinkImageState.Ready ?: return
        val saved = files.save(linkId, imageUrl, File(ready.path))
        // The link may have been deleted while its image downloaded; don't keep a copy nobody owns.
        if (linkDao.updateImage(linkId, imageUrl, saved.path) == 0) saved.delete()
    }

    /** [scope] has no exception handler, so an I/O or Room error escaping it would crash the app. */
    private suspend fun logFailure(action: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "$action failed", e)
        }
    }

    /**
     * Removes thumbnails whose link no longer exists, e.g. when the process died between deleting a
     * link's row and its files. Only files older than a minute are touched, so a thumbnail being
     * attached to a link saved after the id snapshot is never mistaken for an orphan.
     */
    private suspend fun deleteOrphanedImages() {
        val saved = files.saved()
        if (saved.isEmpty()) return
        val cutoff = now() - ORPHAN_MIN_AGE_MS
        val linkIds = linkDao.getAllIds().toHashSet()
        saved
            .filter { it.lastModified() < cutoff && files.linkIdOf(it) !in linkIds }
            .forEach { it.delete() }
    }

    private companion object {
        const val TAG = "LinkThumbnailAttacher"
        const val ORPHAN_MIN_AGE_MS = 60_000L
    }
}
