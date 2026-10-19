package dev.kortex.myinfo.topics.ui.common

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import dev.kortex.design.Sunken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** An image file from app storage, cropped to fill; [Sunken] until it decodes or if it can't. */
@Composable
internal fun LocalThumbnail(path: String, modifier: Modifier = Modifier) {
    val bitmap by produceState<ImageBitmap?>(initialValue = cache.get(path), path) {
        if (value == null) {
            value = withContext(Dispatchers.IO) { decodeSampled(path) }?.also { cache.put(path, it) }
        }
    }
    Box(modifier.background(Sunken)) {
        bitmap?.let { Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
    }
}

/**
 * Tiles are small, so decode at a fraction of full size instead of holding whole photos, and keep
 * only the centre of very tall or wide images (long screenshots, panoramas): the tile crops them anyway.
 */
private fun decodeSampled(path: String): ImageBitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    val options = BitmapFactory.Options().apply { inSampleSize = thumbnailSampleSize(bounds.outWidth, bounds.outHeight) }
    val decoded = BitmapFactory.decodeFile(path, options) ?: return null
    val (width, height) = thumbnailCropSize(decoded.width, decoded.height)
    if (width == decoded.width && height == decoded.height) return decoded.asImageBitmap()
    val cropped = Bitmap.createBitmap(decoded, (decoded.width - width) / 2, (decoded.height - height) / 2, width, height)
    if (cropped !== decoded) decoded.recycle()
    return cropped.asImageBitmap()
}

/** The largest power-of-two step that still leaves the shorter side at least [TARGET_PX]. */
internal fun thumbnailSampleSize(width: Int, height: Int): Int {
    var sample = 1
    while (minOf(width, height) / (sample * 2) >= TARGET_PX) sample *= 2
    return sample
}

/** Centre-crop size: the longer side is held to [MAX_ASPECT] times the shorter one and to [MAX_PX]. */
internal fun thumbnailCropSize(width: Int, height: Int): Pair<Int, Int> {
    val short = minOf(width, height, MAX_PX)
    val long = minOf(maxOf(width, height), short * MAX_ASPECT, MAX_PX)
    return if (width <= height) short to long else long to short
}

private const val TARGET_PX = 256
private const val MAX_PX = 1024
private const val MAX_ASPECT = 3
private const val MAX_CACHE_BYTES = 24 * 1024 * 1024
private val cache = object : LruCache<String, ImageBitmap>(MAX_CACHE_BYTES) {
    override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
}
