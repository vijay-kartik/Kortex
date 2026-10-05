package dev.kortex.finance.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.kortex.design.Edge
import dev.kortex.design.Ink
import dev.kortex.design.Muted
import dev.kortex.design.Sunken
import dev.kortex.design.Synapse

/** One month of the Cash flow chart. */
data class FlowBar(val label: String, val inMinor: Long, val outMinor: Long, val current: Boolean)

/**
 * Cash flow (Figma: Dashboard, final): In (Growth) and Out (Synapse) side by side per month, on
 * three faint gridlines. Past months are dimmed; the current one is full strength.
 */
@Composable
fun CashFlowChart(bars: List<FlowBar>, modifier: Modifier = Modifier) {
    val max = bars.maxOfOrNull { maxOf(it.inMinor, it.outMinor) }?.takeIf { it > 0 } ?: 1L
    Column(modifier.semantics { contentDescription = "Cash flow, last ${bars.size} months" }) {
        Canvas(Modifier.fillMaxWidth().height(120.dp)) {
            val grid = 1.dp.toPx()
            listOf(0.08f, 0.42f, 0.75f).forEach { y ->
                drawRect(Edge.copy(alpha = 0.6f), topLeft = Offset(0f, size.height * y), size = Size(size.width, grid))
            }
            val slot = size.width / bars.size
            val barWidth = 10.dp.toPx()
            val gap = 2.dp.toPx()
            bars.forEachIndexed { index, bar ->
                val center = slot * index + slot / 2
                val alpha = if (bar.current) 1f else 0.45f
                fun bar(value: Long, x: Float, color: Color) {
                    val h = size.height * 0.92f * value / max
                    drawRoundRect(color.copy(alpha = alpha), Offset(x, size.height - h), Size(barWidth, h), CornerRadius(3.dp.toPx()))
                }
                bar(bar.inMinor, center - barWidth - gap / 2, Growth)
                bar(bar.outMinor, center + gap / 2, Synapse)
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 10.dp)) {
            bars.forEach { bar ->
                Text(
                    bar.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (bar.current) Ink else Muted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * Pace vs last month (Figma: Dashboard, below the fold): running spend this month (solid Synapse,
 * ending in a dot on today) against last month (dashed Muted), across the month's days.
 */
@Composable
fun PaceChart(thisMonth: List<Long>, lastMonth: List<Long>, daysInMonth: Int, modifier: Modifier = Modifier) {
    val max = (thisMonth + lastMonth).maxOrNull()?.takeIf { it > 0 } ?: 1L
    Canvas(modifier.fillMaxWidth().height(120.dp).semantics { contentDescription = "Spending pace against last month" }) {
        val grid = 1.dp.toPx()
        listOf(0.07f, 0.5f, 0.93f).forEach { y ->
            drawRect(Edge.copy(alpha = 0.6f), topLeft = Offset(0f, size.height * y), size = Size(size.width, grid))
        }
        val span = (daysInMonth - 1).coerceAtLeast(1)
        fun point(day: Int, value: Long) = Offset(size.width * day / span, size.height * (0.93f - 0.86f * value / max))
        fun path(values: List<Long>) = Path().apply {
            values.forEachIndexed { i, v -> if (i == 0) moveTo(point(i, v).x, point(i, v).y) else lineTo(point(i, v).x, point(i, v).y) }
        }
        if (lastMonth.size > 1) {
            drawPath(
                path(lastMonth),
                Muted,
                style = Stroke(width = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))),
            )
        }
        if (thisMonth.isNotEmpty()) {
            if (thisMonth.size > 1) drawPath(path(thisMonth), Synapse, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
            drawCircle(Synapse, radius = 4.dp.toPx(), center = point(thisMonth.lastIndex, thisMonth.last()))
        }
    }
}

/** A thin segmented bar: Where it went, Pending (card bills vs recurring). */
@Composable
fun SplitBar(segments: List<Pair<Long, Color>>, modifier: Modifier = Modifier, height: androidx.compose.ui.unit.Dp = 8.dp) {
    val total = segments.sumOf { it.first }
    Row(modifier.fillMaxWidth().height(height), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        if (total <= 0) {
            Box(Modifier.fillMaxWidth().fillMaxHeight().clip(RoundedCornerShape(4.dp)).background(Sunken))
        } else {
            segments.filter { it.first > 0 }.forEach { (value, color) ->
                Box(Modifier.weight(value.toFloat()).fillMaxHeight().clip(RoundedCornerShape(4.dp)).background(color))
            }
        }
    }
}

/** Utilisation, Income vs Expenses: a track filled to [fraction]. */
@Composable
fun ProgressTrack(fraction: Float, color: Color, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(Sunken)) {
        Box(
            Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .fillMaxHeight()
                .clip(RoundedCornerShape(4.dp))
                .background(color),
        )
    }
}

/** Last 7 days (Figma: Expenses › Daily): one bar per day, today highlighted. */
@Composable
fun DayBars(values: List<Pair<String, Long>>, modifier: Modifier = Modifier) {
    val max = values.maxOfOrNull { it.second }?.takeIf { it > 0 } ?: 1L
    Column(modifier) {
        Canvas(Modifier.fillMaxWidth().height(90.dp)) {
            val slot = size.width / values.size
            val barWidth = 18.dp.toPx()
            values.forEachIndexed { index, (_, value) ->
                val h = (size.height * value / max).coerceAtLeast(if (value > 0) 2.dp.toPx() else 0f)
                val last = index == values.lastIndex
                drawRoundRect(
                    Synapse.copy(alpha = if (last) 1f else 0.4f),
                    Offset(slot * index + (slot - barWidth) / 2, size.height - h),
                    Size(barWidth, h),
                    CornerRadius(3.dp.toPx()),
                )
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
            values.forEachIndexed { index, (label, _) ->
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (index == values.lastIndex) Ink else Muted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** Savings rate ring (Figma: Monthly report): Growth arc over a Sunken track, percent inside. */
@Composable
fun SavingsRing(percent: Int?, modifier: Modifier = Modifier) {
    Box(modifier.size(64.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(64.dp)) {
            val stroke = 7.dp.toPx()
            val inset = stroke / 2
            val arcSize = Size(size.width - stroke, size.height - stroke)
            drawArc(Sunken, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
            val sweep = 360f * (percent ?: 0).coerceIn(0, 100) / 100f
            if (sweep > 0) drawArc(Growth, -90f, sweep, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
        }
        Text(percent?.let { "$it%" } ?: "—", style = MaterialTheme.typography.labelMedium, color = Ink)
    }
}
