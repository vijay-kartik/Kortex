package dev.kortex.myinfo.topics.ui.common

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

/** Tiles are small, so decode at a fraction of full size instead of holding whole photos. */
private fun decodeSampled(path: String): ImageBitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= TARGET_PX) sample *= 2
    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    return BitmapFactory.decodeFile(path, options)?.asImageBitmap()
}

private const val TARGET_PX = 256
private val cache = LruCache<String, ImageBitmap>(48)
