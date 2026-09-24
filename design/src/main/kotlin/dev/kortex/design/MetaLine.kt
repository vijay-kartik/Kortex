package dev.kortex.design

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp

/** The mono count line at the top of a list, e.g. "4 LINKS · 3 TAGS". */
@Composable
fun MetaLine(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MetaStyle, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = modifier)
}

/** "1 LINK", "3 LINKS": [noun] is the singular, in capitals. */
fun countLabel(count: Int, noun: String) = "$count ${if (count == 1) noun else noun + "S"}"

/** "2 RESULTS FOR “GOO”", for a list filtered by a search. */
fun resultsLabel(count: Int, query: String) = "${countLabel(count, "RESULT")} FOR “${query.trim().uppercase()}”"

private val MetaStyle = TextStyle(
    fontFamily = Mono,
    fontWeight = FontWeight.Medium,
    fontSize = 12.sp,
    lineHeight = 16.sp,
    letterSpacing = 1.sp,
)
