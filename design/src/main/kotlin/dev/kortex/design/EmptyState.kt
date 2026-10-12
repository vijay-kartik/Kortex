package dev.kortex.design

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * A screen's empty state (Figma: Chat / Empty, Links / Empty): an optional [glyph] over a
 * centred title and a muted line telling the user how to start.
 */
@Composable
fun EmptyState(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    glyph: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        glyph?.invoke()
        Text(title, style = EmptyTitleStyle, color = Ink, textAlign = TextAlign.Center)
        Text(body, style = EmptyBodyStyle, color = Muted, textAlign = TextAlign.Center)
    }
}

private val EmptyTitleStyle = TextStyle(
    fontFamily = Grotesk,
    fontWeight = FontWeight.Medium,
    fontSize = 19.sp,
    lineHeight = 24.sp,
)

private val EmptyBodyStyle = TextStyle(
    fontFamily = Grotesk,
    fontWeight = FontWeight.Normal,
    fontSize = 14.sp,
    lineHeight = 20.sp,
)
