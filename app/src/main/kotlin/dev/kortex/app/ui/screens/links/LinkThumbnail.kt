package dev.kortex.app.ui.screens.links

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kortex.app.R
import dev.kortex.app.ui.Alarm
import dev.kortex.app.ui.Grotesk
import dev.kortex.app.ui.Mono
import dev.kortex.app.ui.anim.StandardEasing
import dev.kortex.app.ui.Synapse
import dev.kortex.app.ui.SynapseDim
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// ── Card geometry and type, shared by the Links list and the New link preview ──

// 1dp border + 14dp padding: in Figma the border sits outside the padding.
internal val LinkCardInset = 15.dp
internal val LinkCardShape = RoundedCornerShape(14.dp)

/** Matches the text column (title 24 + url 16 + meta 16 + 2 × 6 gap), so cards stay 98dp tall. */
internal val LinkThumbnailSize = 68.dp
internal val LinkThumbnailShape = RoundedCornerShape(10.dp)

internal val LinkTitleStyle = TextStyle(fontFamily = Grotesk, fontWeight = FontWeight.Medium, fontSize = 17.sp, lineHeight = 24.sp, letterSpacing = 0.15.sp)
internal val LinkUrlStyle = TextStyle(fontFamily = Mono, fontSize = 12.sp, lineHeight = 16.sp)
internal val LinkMetaStyle = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 16.sp, letterSpacing = 1.2.sp)

/** What a card's thumbnail shows, before the user's hide/show choice is applied. */
internal sealed interface ThumbnailContent {
    /** The page itself is still being read. */
    data object ReadingPage : ThumbnailContent

    /** [fraction] is null when the image's size isn't known, which keeps the ring indeterminate. */
    data class LoadingImage(val fraction: Float?) : ThumbnailContent

    data class Image(val path: String) : ThumbnailContent

    data object Failed : ThumbnailContent

    data object Glyph : ThumbnailContent
}

/**
 * The 68dp thumbnail tile. Changes of content crossfade in 200ms; [hidden] runs the 240ms
 * Hide image motion (Figma: Image toggle / Spec) — the content shrinks to 0.92 and fades while
 * the glyph tile fades in over the first two thirds and its icon grows in over the last two.
 */
@Composable
internal fun LinkThumbnail(content: ThumbnailContent, hidden: Boolean, modifier: Modifier = Modifier) {
    val hide by animateFloatAsState(
        targetValue = if (hidden) 1f else 0f,
        animationSpec = tween(HIDE_MS, easing = StandardEasing),
        label = "thumbnail hide",
    )
    Box(modifier.size(LinkThumbnailSize).clip(LinkThumbnailShape)) {
        if (hide < 1f) {
            AnimatedContent(
                targetState = content,
                // Progress updates are the same content; only a change of kind crossfades.
                contentKey = { it::class },
                transitionSpec = { fadeIn(tween(CONTENT_FADE_MS)) togetherWith fadeOut(tween(CONTENT_FADE_MS)) },
                modifier = Modifier.graphicsLayer {
                    alpha = 1f - hide
                    val scale = 1f + (HIDDEN_SCALE - 1f) * hide
                    scaleX = scale
                    scaleY = scale
                },
                label = "thumbnail content",
            ) { shown ->
                when (shown) {
                    ThumbnailContent.ReadingPage -> ProgressTile(fraction = null, showGlobe = true)
                    is ThumbnailContent.LoadingImage -> ProgressTile(fraction = shown.fraction, showGlobe = false)
                    is ThumbnailContent.Image -> ThumbnailImage(shown.path)
                    ThumbnailContent.Failed -> FailedTile()
                    ThumbnailContent.Glyph -> GlyphTile()
                }
            }
        }
        if (hide > 0f) {
            val tile = (hide / TILE_SHARE).coerceIn(0f, 1f)
            val icon = ((hide - (1f - ICON_SHARE)) / ICON_SHARE).coerceIn(0f, 1f)
            GlyphTile(
                modifier = Modifier.graphicsLayer { alpha = tile },
                iconModifier = Modifier.graphicsLayer {
                    alpha = icon
                    val scale = HIDDEN_ICON_START_SCALE + (1f - HIDDEN_ICON_START_SCALE) * icon
                    scaleX = scale
                    scaleY = scale
                },
            )
        }
    }
}

@Composable
internal fun GlyphTile(modifier: Modifier = Modifier, iconModifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().background(SynapseDim), contentAlignment = Alignment.Center) {
        Icon(
            painterResource(R.drawable.ic_link),
            contentDescription = null,
            tint = Synapse,
            modifier = iconModifier.size(26.dp),
        )
    }
}

/** Centre-cropped; the tile stays SynapseDim for the moment it takes to decode. */
@Composable
internal fun ThumbnailImage(path: String, modifier: Modifier = Modifier) {
    val bitmap = rememberThumbnailBitmap(path)
    Box(modifier.fillMaxSize().background(SynapseDim)) {
        if (bitmap != null) {
            Image(bitmap, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun FailedTile() {
    Box(
        Modifier
            .fillMaxSize()
            .background(Alarm.copy(alpha = 0.1f))
            .drawBehind {
                val stroke = 1.dp.toPx()
                drawRoundRect(
                    color = Alarm.copy(alpha = 0.45f),
                    topLeft = Offset(stroke / 2, stroke / 2),
                    size = Size(size.width - stroke, size.height - stroke),
                    cornerRadius = CornerRadius(10.dp.toPx() - stroke / 2),
                    style = Stroke(stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))),
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(R.drawable.ic_image_off), contentDescription = null, tint = Alarm, modifier = Modifier.size(24.dp))
    }
}

/**
 * 36dp ring on SynapseDim. Without a [fraction] a 100° arc turns every 900ms; with one the arc
 * fills clockwise from the top and the percentage sits inside.
 */
@Composable
private fun ProgressTile(fraction: Float?, showGlobe: Boolean) {
    // Only an indeterminate ring spins. Both values are read while drawing, so they redraw the ring
    // without recomposing the tile every frame.
    val spin = if (fraction == null) {
        rememberInfiniteTransition(label = "ring spin").animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(SPIN_MS, easing = LinearEasing)),
            label = "ring rotation",
        )
    } else {
        null
    }
    val shownFraction = animateFloatAsState(fraction ?: 0f, tween(PROGRESS_MS), label = "ring fraction")

    Box(Modifier.fillMaxSize().background(SynapseDim), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(RING_SIZE)) {
            val stroke = RING_STROKE.toPx()
            val arcSize = Size(size.width - stroke, size.height - stroke)
            val topLeft = Offset(stroke / 2, stroke / 2)
            drawArc(Synapse.copy(alpha = 0.22f), 0f, 360f, useCenter = false, topLeft = topLeft, size = arcSize, style = Stroke(stroke))
            if (spin != null) {
                drawArc(Synapse, spin.value - 90f, INDETERMINATE_SWEEP, useCenter = false, topLeft = topLeft, size = arcSize, style = Stroke(stroke))
            } else {
                drawArc(Synapse, -90f, 360f * shownFraction.value, useCenter = false, topLeft = topLeft, size = arcSize, style = Stroke(stroke))
            }
        }
        when {
            fraction != null -> Text(
                "${(fraction * 100).toInt()}%",
                style = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Medium, fontSize = 10.sp, lineHeight = 12.sp),
                color = Synapse,
            )
            showGlobe -> Icon(painterResource(R.drawable.ic_globe), contentDescription = null, tint = Synapse, modifier = Modifier.size(14.dp))
        }
    }
}

// Sized for ~90 thumbnails at 256px, well over a screenful.
private val thumbnailCache = object : LruCache<String, ImageBitmap>(24 * 1024 * 1024) {
    override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
}

/** A cache hit is ready on the first frame, so cards scrolled back into view don't flash the tile. */
@Composable
private fun rememberThumbnailBitmap(path: String): ImageBitmap? {
    val bitmap by produceState(initialValue = thumbnailCache.get(path), path) {
        if (value == null) {
            value = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(path)?.asImageBitmap() }
                ?.also { thumbnailCache.put(path, it) }
        }
    }
    return bitmap
}

private const val HIDE_MS = 240
private const val CONTENT_FADE_MS = 200
private const val PROGRESS_MS = 200
private const val SPIN_MS = 900
private const val HIDDEN_SCALE = 0.92f
private const val HIDDEN_ICON_START_SCALE = 0.8f

/** The glyph tile fades in over 0–160ms of 240. */
private const val TILE_SHARE = 160f / 240f

/** The glyph icon grows in over 80–240ms of 240. */
private const val ICON_SHARE = 160f / 240f

private val RING_SIZE = 36.dp
private val RING_STROKE = 2.5.dp
private const val INDETERMINATE_SWEEP = 100f
