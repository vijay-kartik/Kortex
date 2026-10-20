package dev.kortex.links.images

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import dev.kortex.links.domain.model.LinkImageState
import java.io.File
import java.io.IOException
import javax.inject.Inject
import kotlin.math.min
import kotlin.math.roundToInt

/** Turns a downloaded image into a stored thumbnail. */
class ThumbnailShrinker @Inject constructor() {

    /** Decodes at the smallest sample that still covers the thumbnail, then stores a JPEG. */
    fun shrink(original: File, target: File): LinkImageState {
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

    private companion object {
        /** Shortest side of a stored thumbnail: the 68dp card thumbnail at xxxhdpi, with room to spare. */
        const val THUMBNAIL_PX = 256
        const val JPEG_QUALITY = 85
    }
}
