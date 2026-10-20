package dev.kortex.links.images

import dev.kortex.links.tagging.FETCH_TIMEOUT_MS
import dev.kortex.links.tagging.FETCH_USER_AGENT
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import kotlin.coroutines.coroutineContext

/** Downloads an image to a file. */
interface ImageFetcher {
    /** Writes [imageUrl] to [target]; [onProgress] gets 0–1, or null while the size is unknown. Blocks: call off the main thread. */
    suspend fun fetch(imageUrl: String, target: File, onProgress: (Float?) -> Unit)
}

class HttpImageFetcher @Inject constructor() : ImageFetcher {

    override suspend fun fetch(imageUrl: String, target: File, onProgress: (Float?) -> Unit) {
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

    private companion object {
        const val MAX_BYTES = 10L * 1024 * 1024
    }
}
