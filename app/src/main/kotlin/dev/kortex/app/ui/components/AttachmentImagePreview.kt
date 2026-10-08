package dev.kortex.app.ui.components

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import dev.kortex.core.state.Attachment
import dev.kortex.design.Alarm
import dev.kortex.design.Muted
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private sealed interface PreviewImage {
    data object Loading : PreviewImage
    data object Failed : PreviewImage
    data class Ready(val bitmap: ImageBitmap) : PreviewImage
}

/** Full-screen preview of an image attachment, decoded off the main thread at roughly screen size. */
@Composable
internal fun AttachmentImagePreview(att: Attachment) {
    val config = LocalConfiguration.current
    val density = LocalDensity.current
    val maxPx = with(density) { maxOf(config.screenWidthDp, config.screenHeightDp).dp.roundToPx() }
    val image by produceState<PreviewImage>(PreviewImage.Loading, att.dataBase64, maxPx) {
        value = withContext(Dispatchers.IO) { decodeSampled(att.dataBase64, maxPx) }
            ?.let { PreviewImage.Ready(it) } ?: PreviewImage.Failed
    }
    when (val img = image) {
        PreviewImage.Loading -> Text("Loading image...", color = Muted)
        PreviewImage.Failed -> Text("Failed to load image.", color = Alarm)
        is PreviewImage.Ready -> Image(
            bitmap = img.bitmap,
            contentDescription = att.filename,
            modifier = Modifier.fillMaxSize().padding(16.dp),
            contentScale = ContentScale.Fit
        )
    }
}

/** Attachments hold the original camera bytes, so decode at a fraction of full size instead of the whole photo. */
private fun decodeSampled(base64: String, maxPx: Int): ImageBitmap? = try {
    val bytes = Base64.decode(base64, Base64.DEFAULT)
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
        null
    } else {
        val options = BitmapFactory.Options().apply {
            inSampleSize = previewSampleSize(bounds.outWidth, bounds.outHeight, maxPx)
        }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
    }
} catch (e: IllegalArgumentException) {
    null
} catch (e: OutOfMemoryError) {
    null
}

/**
 * Largest power-of-two sample size that keeps the longest side at or above [maxPx], so the
 * decoded bitmap is never much bigger than the screen but still fills it without blurring.
 */
internal fun previewSampleSize(width: Int, height: Int, maxPx: Int): Int {
    val longest = maxOf(width, height)
    var sample = 1
    while (longest / (sample * 2) >= maxPx.coerceAtLeast(1)) sample *= 2
    return sample
}
