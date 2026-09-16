package dev.kortex.app.ui.screens.links

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import dev.kortex.app.ui.Edge
import dev.kortex.app.ui.EmphasizedAccelerate
import dev.kortex.app.ui.EmphasizedDecelerate
import dev.kortex.app.ui.StandardEasing
import dev.kortex.app.ui.Synapse
import dev.kortex.app.ui.SynapseDim
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * "Copy on tap" motion for a link card (Figma: Copy anim / Spec), 1960ms:
 *
 * 1. 0–200ms, emphasized-accelerate: glyph fills and swaps link → check, ring is born
 *    at the glyph and grows 36 → 50dp, border flashes, sweep starts.
 * 2. 200–520ms, emphasized-decelerate: ring grows 50 → 64dp and fades, border settles,
 *    sweep leaves the card, url line crossfades to "→ clipboard".
 * 3. 520–1720ms: hold.
 * 4. 1720–1960ms, standard: glyph and url line return.
 *
 * The card's fill never changes, so "copied" can't read as "selected".
 */
@Stable
internal class LinkCopyAnimation {
    /**
     * Phases 1 and 2 share one track. Phase 1 covers [PHASE_1_SHARE] of it in 200 of 520ms,
     * the same share as the sweep's distance, so the sweep keeps its speed across the hand-off.
     */
    private val progress = Animatable(0f)
    private val urlSwap = Animatable(0f)
    private val release = Animatable(0f)

    private val phase1 get() = (progress.value / PHASE_1_SHARE).coerceIn(0f, 1f)
    private val phase2 get() = ((progress.value - PHASE_1_SHARE) / (1f - PHASE_1_SHARE)).coerceIn(0f, 1f)

    /** Rises through phase 1, falls through phase 2. */
    private val flash get() = if (progress.value <= PHASE_1_SHARE) phase1 else 1f - phase2

    /** 0 = link glyph on SynapseDim, 1 = check on Synapse. */
    val glyph get() = phase1 * (1f - release.value)

    /** 0 = the url, 1 = "→ clipboard". */
    val urlLine get() = urlSwap.value * (1f - release.value)

    val glyphFill get() = lerp(SynapseDim, Synapse, glyph)

    val borderColor get() = lerp(Edge, Synapse.copy(alpha = 0.55f), flash)

    suspend fun play() = coroutineScope {
        reset()
        launch {
            delay(PHASE_1_MS.toLong())
            urlSwap.animateTo(1f, tween(URL_CROSSFADE_MS, easing = EmphasizedDecelerate))
        }
        progress.animateTo(
            1f,
            keyframes {
                durationMillis = PHASE_2_END_MS
                // A keyframe's easing applies to the segment that starts at it.
                0f at 0 using EmphasizedAccelerate
                PHASE_1_SHARE at PHASE_1_MS using EmphasizedDecelerate
            },
        )
        delay(HOLD_MS)
        release.animateTo(1f, tween(RELEASE_MS, easing = StandardEasing))
        // Sweep and ring are already invisible, so snapping back is unseen.
        reset()
    }

    suspend fun reset() {
        progress.snapTo(0f)
        urlSwap.snapTo(0f)
        release.snapTo(0f)
    }

    /** Sweep and pulse ring, drawn behind the card content. [glyphCenter] is in card coordinates. */
    fun DrawScope.drawEffects(glyphCenter: Offset) {
        val t = progress.value
        if (t > 0f && t < 1f) {
            val left = lerp(SWEEP_START_X, SWEEP_END_X, t).toPx()
            val width = SWEEP_WIDTH.toPx()
            drawRect(
                brush = Brush.horizontalGradient(
                    0f to SweepClear,
                    0.42f to SweepClear,
                    0.60f to Synapse.copy(alpha = 0.26f),
                    0.78f to SweepClear,
                    1f to SweepClear,
                    startX = left,
                    endX = left + width,
                ),
                topLeft = Offset(left, 0f),
                size = Size(width, size.height),
            )
        }

        val ringAlpha = 0.5f * flash
        if (ringAlpha > 0f) {
            val side = lerp(lerp(RING_START, RING_MID, phase1), RING_END, phase2).toPx()
            val stroke = lerp(RING_STROKE, RING_STROKE_END, phase2).toPx()
            // Stroke sits inside the ring's box, like the Figma border.
            val inset = stroke / 2
            drawRoundRect(
                color = Synapse.copy(alpha = ringAlpha),
                topLeft = Offset(glyphCenter.x - side / 2 + inset, glyphCenter.y - side / 2 + inset),
                size = Size(side - stroke, side - stroke),
                // Corners scale with size so the ring stays a glyph echo instead of rounding into a circle.
                cornerRadius = CornerRadius(side * RING_CORNER_RATIO - inset),
                style = Stroke(stroke),
            )
        }
    }

    private companion object {
        const val PHASE_1_MS = 200
        const val PHASE_2_END_MS = 520
        const val HOLD_MS = 1_200L
        const val RELEASE_MS = 240
        const val URL_CROSSFADE_MS = 200
        const val PHASE_1_SHARE = 150f / 390f

        val SWEEP_START_X = (-90).dp
        val SWEEP_END_X = 300.dp
        val SWEEP_WIDTH = 380.dp
        val SweepClear = Synapse.copy(alpha = 0f)

        val RING_START = 36.dp
        val RING_MID = 50.dp
        // Ceiling: any larger and the ring crosses the card's top edge and gets clipped flat.
        val RING_END = 64.dp
        val RING_STROKE = 1.5.dp
        val RING_STROKE_END = 0.75.dp
        const val RING_CORNER_RATIO = 10f / 36f
    }
}

/**
 * Runs the copy animation whenever [copyTick] changes. A null tick (another card was tapped)
 * cancels a running animation and snaps this card back to rest.
 */
@Composable
internal fun rememberLinkCopyAnimation(copyTick: Int?, onFinished: () -> Unit): LinkCopyAnimation {
    val animation = remember { LinkCopyAnimation() }
    val currentOnFinished by rememberUpdatedState(onFinished)
    LaunchedEffect(copyTick) {
        if (copyTick == null) {
            animation.reset()
        } else {
            animation.play()
            currentOnFinished()
        }
    }
    return animation
}
