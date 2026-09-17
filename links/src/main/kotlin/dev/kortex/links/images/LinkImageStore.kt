package dev.kortex.links.images

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.kortex.links.data.LinkDao
import dev.kortex.links.tagging.FETCH_TIMEOUT_MS
import dev.kortex.links.tagging.FETCH_USER_AGENT
import dev.kortex.links.tagging.PageMetadataFetcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext
import kotlin.math.min
import kotlin.math.roundToInt

sealed interface LinkImageState {
    /** [fraction] is null while the server hasn't said how big the image is. */
    data class Loading(val fraction: Float?) : LinkImageState

    /** [width] × [height] are the original image's, before it was shrunk to a thumbnail. */
    data class Ready(val path: String, val width: Int, val height: Int) : LinkImageState

    data object Failed : LinkImageState
}

/** What the page said about its share image when the link was saved. */
sealed interface LinkImageSource {
    data class Known(val imageUrl: String) : LinkImageSource

    /** The page was read and names no image. */
    data object None : LinkImageSource

    /** Saved before the page was read; the image is looked up afterwards. */
    data object Unknown : LinkImageSource
}

/**
 * Downloads page share images and shrinks them to thumbnails. Downloads are shared by address and
 * outlive the screen that started them, so saving a link mid-download doesn't restart or lose it.
 */
@Singleton
class LinkImageStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val linkDao: LinkDao,
    private val metadataFetcher: PageMetadataFetcher,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val downloads = ConcurrentHashMap<String, MutableStateFlow<LinkImageState>>()

    init {
        scope.launch { deleteOrphanedImages() }
    }

    /** Starts downloading [imageUrl] unless it already is (or has). */
    fun image(imageUrl: String): StateFlow<LinkImageState> {
        val state = downloads.computeIfAbsent(imageUrl) { MutableStateFlow<LinkImageState>(LinkImageState.Loading(null)).also { start(imageUrl, it) } }
        // The system may clear the cache under a finished download.
        val current = state.value
        if (current is LinkImageState.Ready && !File(current.path).exists()) retry(imageUrl)
        return state
    }

    /** Restarts a failed download. Does nothing while one is running or after one succeeded. */
    fun retry(imageUrl: String) {
        val state = downloads[imageUrl] ?: run {
            image(imageUrl)
            return
        }
        val current = state.value
        if (current is LinkImageState.Loading) return
        if (current is LinkImageState.Ready && File(current.path).exists()) return
        state.value = LinkImageState.Loading(null)
        start(imageUrl, state)
    }

    /** Gives a just-saved link its thumbnail once one is available; a failure leaves the link icon. */
    fun attachWhenReady(linkId: Long, pageUrl: String, source: LinkImageSource) {
        scope.launch {
            val imageUrl = when (source) {
                is LinkImageSource.Known -> source.imageUrl
                LinkImageSource.None -> return@launch
                LinkImageSource.Unknown -> {
                    val found = metadataFetcher.fetch(pageUrl).imageUrl ?: return@launch
                    // Deleted while the page was being read: nothing to attach to.
                    if (linkDao.updateImage(linkId, found, imagePath = null) == 0) return@launch
                    found
                }
            }
            val ready = image(imageUrl).first { it !is LinkImageState.Loading } as? LinkImageState.Ready ?: return@launch
            val saved = File(imagesDir(), "${imageFilePrefix(linkId)}${sha1(imageUrl).take(12)}.jpg")
            File(ready.path).copyTo(saved, overwrite = true)
            // The link may have been deleted while its image downloaded; don't keep a copy nobody owns.
            if (linkDao.updateImage(linkId, imageUrl, saved.path) == 0) saved.delete()
        }
    }

    /** Deletes every stored thumbnail of [linkId] from app storage. Call once its row is deleted. */
    suspend fun deleteImages(linkId: Long) {
        withContext(Dispatchers.IO) {
            val prefix = imageFilePrefix(linkId)
            imagesDir().listFiles { file -> file.name.startsWith(prefix) }?.forEach { it.delete() }
        }
    }

    /**
     * Removes thumbnails whose link no longer exists, e.g. when the process died between deleting a
     * link's row and its files. Only files older than a minute are touched, so a thumbnail being
     * attached to a link saved after the id snapshot is never mistaken for an orphan.
     */
    private suspend fun deleteOrphanedImages() {
        val files = imagesDir().listFiles().orEmpty()
        if (files.isEmpty()) return
        val cutoff = System.currentTimeMillis() - ORPHAN_MIN_AGE_MS
        val linkIds = linkDao.getAllIds().toHashSet()
        files
            .filter { it.lastModified() < cutoff && it.name.substringBefore('-').toLongOrNull() !in linkIds }
            .forEach { it.delete() }
    }

    /** Thumbnail files are named `<linkId>-<hash>.jpg`; ids are never reused, so the prefix owns them. */
    private fun imageFilePrefix(linkId: Long) = "$linkId-"

    private fun start(imageUrl: String, state: MutableStateFlow<LinkImageState>) {
        scope.launch {
            state.value = try {
                withTimeout(FETCH_TIMEOUT_MS.toLong()) { download(imageUrl) { state.value = LinkImageState.Loading(it) } }
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
        val draft = File(draftsDir(), "${sha1(imageUrl)}.jpg")
        val original = File(draftsDir(), "${sha1(imageUrl)}.download")
        try {
            fetch(imageUrl, original, onProgress)
            shrink(original, draft)
        } finally {
            original.delete()
        }
    }

    private suspend fun fetch(imageUrl: String, target: File, onProgress: (Float?) -> Unit) {
        // Pages often still declare http:// image addresses, which Android refuses to load
        // (cleartext is off). The same CDN almost always serves https, so ask for that instead.
        val secureUrl = if (imageUrl.startsWith("http://")) "https://" + imageUrl.removePrefix("http://") else imageUrl
        val connection = URL(secureUrl).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = FETCH_TIMEOUT_MS
            connection.readTimeout = FETCH_TIMEOUT_MS
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", FETCH_USER_AGENT)
            if (connection.responseCode !in 200..299) throw IOException("HTTP ${connection.responseCode}")
            val total = connection.contentLengthLong.takeIf { it > 0 }
            if (total != null && total > MAX_BYTES) throw IOException("Image too large: $total bytes")
            onProgress(total?.let { 0f })

            connection.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(16 * 1024)
                    var read = 0L
                    var lastPercent = -1
                    while (true) {
                        // Blocking reads don't notice cancellation, so check between chunks.
                        coroutineContext.ensureActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        read += n
                        if (read > MAX_BYTES) throw IOException("Image too large")
                        if (total != null) {
                            val percent = (read * 100 / total).toInt().coerceAtMost(100)
                            if (percent != lastPercent) {
                                lastPercent = percent
                                onProgress(percent / 100f)
                            }
                        }
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    /** Decodes at the smallest sample that still covers the thumbnail, then stores a JPEG. */
    private fun shrink(original: File, target: File): LinkImageState {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(original.path, bounds)
        val width = bounds.outWidth
        val height = bounds.outHeight
        if (width <= 0 || height <= 0) throw IOException("Not a decodable image")

        var sample = 1
        while (min(width, height) / (sample * 2) >= THUMBNAIL_PX) sample *= 2
        val decoded = BitmapFactory.decodeFile(original.path, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw IOException("Not a decodable image")
        val scale = THUMBNAIL_PX.toFloat() / min(decoded.width, decoded.height)
        val thumbnail = if (scale < 1f) {
            Bitmap.createScaledBitmap(decoded, (decoded.width * scale).roundToInt(), (decoded.height * scale).roundToInt(), true)
        } else {
            decoded
        }
        target.outputStream().use { thumbnail.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
        if (thumbnail !== decoded) thumbnail.recycle()
        decoded.recycle()
        return LinkImageState.Ready(target.path, width, height)
    }

    // Unsaved downloads can go whenever the system needs space; saved ones are copied out.
    private fun draftsDir() = File(context.cacheDir, "link-image-drafts").apply { mkdirs() }

    private fun imagesDir() = File(context.filesDir, "link-images").apply { mkdirs() }

    private companion object {
        const val TAG = "LinkImageStore"

        /** Shortest side of a stored thumbnail: the 68dp card thumbnail at xxxhdpi, with room to spare. */
        const val THUMBNAIL_PX = 256
        const val JPEG_QUALITY = 85
        const val MAX_BYTES = 10L * 1024 * 1024
        const val ORPHAN_MIN_AGE_MS = 60_000L
    }
}

private fun sha1(value: String): String =
    MessageDigest.getInstance("SHA-1").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
