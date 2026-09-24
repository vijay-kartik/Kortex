package dev.kortex.myinfo.topics.ui.list.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kortex.design.Edge
import dev.kortex.design.Muted
import dev.kortex.design.Panel
import dev.kortex.myinfo.topics.ui.common.BodyStyle

/** Opens search across every topic (Figma: Topics 1a, 1f). A pill, not a field: the screen it
 * opens owns the query and the keyboard. */
@Composable
internal fun SearchPill(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Panel)
            .border(1.dp, Edge, shape)
            .clickable(onClickLabel = "Search topics", role = Role.Button, onClick = onClick)
            .heightIn(min = 48.dp)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Decoration: the words say what the pill does, so TalkBack skips the glyph.
        Text("⌕", style = BodyStyle.copy(fontSize = 17.sp), color = Muted, modifier = Modifier.clearAndSetSemantics { })
        Text("Search notes, links, files and bills", style = BodyStyle, color = Muted)
    }
}
