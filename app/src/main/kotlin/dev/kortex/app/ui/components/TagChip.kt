package dev.kortex.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import dev.kortex.app.ui.Edge
import dev.kortex.app.ui.Ink
import dev.kortex.app.ui.Panel
import dev.kortex.app.ui.Synapse

@Composable
fun TagChip(text: String) {
    val shape = RoundedCornerShape(8.dp)
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = Ink,
        modifier = Modifier
            .clip(shape)
            .background(Panel)
            .border(1.dp, Edge, shape)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    )
}

@Composable
fun NewTagChip(onClick: () -> Unit) {
    Text(
        "+ new tag",
        style = MaterialTheme.typography.labelLarge,
        color = Synapse,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .drawBehind {
                val stroke = 1.dp.toPx()
                drawRoundRect(
                    color = Edge,
                    topLeft = Offset(stroke / 2, stroke / 2),
                    size = Size(size.width - stroke, size.height - stroke),
                    cornerRadius = CornerRadius(8.dp.toPx()),
                    style = Stroke(
                        width = stroke,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())),
                    ),
                )
            }
            .padding(start = 10.dp, end = 12.dp, top = 7.dp, bottom = 7.dp),
    )
}