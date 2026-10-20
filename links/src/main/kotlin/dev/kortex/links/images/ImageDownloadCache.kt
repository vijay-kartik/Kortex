package dev.kortex.links.images

import android.util.Log
import dev.kortex.links.domain.model.LinkImageState
import dev.kortex.links.domain.port.ImageDownloads
import dev.kortex.links.tagging.FETCH_TIMEOUT_MS
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Downloads page share images and shrinks them to thumbnails. Downloads are shared by address and
 * outlive the screen that started them, so saving a link mid-download doesn't restart or lose it.
 */
@Singleton
class ImageDownloadCache @Inject constructor(
    private val fetcher: ImageFetcher,
    private val shrinker: ThumbnailShrinker,
    private val files: ThumbnailFiles,
) : ImageDownloads {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val downloads = ConcurrentHashMap<String, MutableStateFlow<LinkImageState>>()
    private val slots = DownloadSlots(MAX_PARALLEL_DOWNLOADS, FETCH_TIMEOUT_MS.toLong())

    /** Starts downloading [imageUrl] unless it already is (or has). */
    override fun image(imageUrl: String): StateFlow<LinkImageState> {
        val state = downloads.computeIfAbsent(imageUrl) { MutableStateFlow<LinkImageState>(LinkImageState.Loading(null)).also { start(imageUrl, it) } }
        // The system may clear the cache under a finished download.
        val current = state.value
        if (current is LinkImageState.Ready && !File(current.path).exists()) retry(imageUrl)
        return state
    }

    /** Restarts a failed download. Does nothing while one is running or after one succeeded. */
    override fun retry(imageUrl: String) {
        val state = downloads[imageUrl] ?: run {
            image(imageUrl)
            return
        }
        val current = state.value
        if (!needsRestart(current) { File(it).exists() }) return
        // Of several callers retrying at once, only one starts the download.
        if (!state.compareAndSet(current, LinkImageState.Loading(null))) return
        start(imageUrl, state)
    }

    private fun start(imageUrl: String, state: MutableStateFlow<LinkImageState>) {
        scope.launch {
            state.value = try {
                slots.run { download(imageUrl) { state.value = LinkImageState.Loading(it) } }
            } catch (e: TimeoutCancellationException) {
                LinkImageState.Failed
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Image download failed for $imageUrl", e)
                LinkImageState.Failed
            }
        }
    }

    private suspend fun download(imageUrl: String, onProgress: (Float?) -> Unit): LinkImageState = withContext(Dispatchers.IO) {
        val draft = files.draft(imageUrl)
        val original = files.original(imageUrl)
        try {
            fetcher.fetch(imageUrl, original, onProgress)
            shrinker.shrink(original, draft)
        } finally {
            original.delete()
        }
    }

    private companion object {
        const val TAG = "ImageDownloadCache"
        const val MAX_PARALLEL_DOWNLOADS = 4
    }
}
