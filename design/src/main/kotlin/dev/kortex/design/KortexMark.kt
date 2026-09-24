package dev.kortex.design

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.geometry.toRect
import dev.kortex.design.anim.EmphasizedDecelerate

// Geometry of ic_kortex_mark / ic_launcher_foreground, in their 108-unit adaptive-icon viewport.
private const val VIEWPORT = 108f
private const val NODE_RADIUS = 8f
private const val EDGE_WIDTH = 10f
private val TopLeft = Offset(41f, 36f)
private val TopRight = Offset(67f, 36f)
private val BottomLeft = Offset(41f, 72f)
private val BottomRight = Offset(67f, 72f)
private val Junction = Offset(41f, 54f)
private val Terminals = listOf(TopRight, BottomRight)

/**
 * Draws the Kortex mark with its 108-unit viewport filling [bounds], so drawing it over the
 * launcher icon's bounds matches the icon exactly.
 *
 * [fire] runs 0→1 for the "synapse fire" beat: a pulse leaves the junction along both edges, then
 * the two terminal nodes ring. At 1 the mark is at rest.
 */
fun DrawScope.drawKortexMark(bounds: Rect, color: Color = Synapse, fire: Float = 1f) {
    val scale = bounds.width / VIEWPORT
    fun at(p: Offset) = Offset(bounds.left + p.x * scale, bounds.top + p.y * scale)
    val edge = EDGE_WIDTH * scale

    drawLine(color, at(TopLeft), at(BottomLeft), edge, StrokeCap.Round)
    for (end in Terminals) drawLine(color, at(Junction), at(end), edge, StrokeCap.Round)
    for (node in listOf(TopLeft, TopRight, BottomLeft, BottomRight)) drawCircle(color, NODE_RADIUS * scale, at(node))

    if (fire >= 1f) return

    // The pulse lights a filament down the middle of each edge, then fades once it arrives.
    val travel = EmphasizedDecelerate.transform((fire / 0.55f).coerceIn(0f, 1f))
    val fade = 1f - ((fire - 0.55f) / 0.45f).coerceIn(0f, 1f)
    if (fade > 0f) {
        val lit = lerp(color, Ink, 0.65f).copy(alpha = fade)
        for (end in Terminals) {
            val tip = lerp(Junction, end, travel)
            drawLine(lit, at(Junction), at(tip), edge * 0.4f, StrokeCap.Round)
            drawCircle(lit, 3.2f * scale, at(tip))
        }
    }

    // On arrival each terminal node sends out a ring.
    val ring = ((fire - 0.45f) / 0.55f).coerceIn(0f, 1f)
    if (ring > 0f) {
        val radius = (NODE_RADIUS + 14f * EmphasizedDecelerate.transform(ring)) * scale
        val stroke = Stroke(width = 1.5f * scale)
        for (end in Terminals) drawCircle(color.copy(alpha = 0.5f * (1f - ring)), radius, at(end), style = stroke)
    }
}

/** The mark at rest. Size this 1.5× the visible icon (a 72dp icon slot takes a 108dp mark). */
@Composable
fun KortexMark(modifier: Modifier = Modifier) {
    Canvas(modifier.semantics { contentDescription = "Kortex" }) {
        drawKortexMark(size.toRect())
    }
}
