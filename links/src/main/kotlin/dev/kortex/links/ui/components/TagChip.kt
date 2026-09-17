package dev.kortex.links.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kortex.design.Edge
import dev.kortex.design.Ink
import dev.kortex.design.Mono
import dev.kortex.design.Muted
import dev.kortex.design.Panel
import dev.kortex.design.Synapse
import dev.kortex.design.SynapseDim
import dev.kortex.design.dashedBorder

/**
 * Tag pill. Selected tags take the synapse accent; [suggested] (unselected) ones keep ink text and
 * are marked with a leading accent dot, so a proposed tag never looks like an applied one.
 * [count] adds a trailing mono tally (e.g. links per tag).
 * Pass [onSelectedChange] to make it toggleable.
 */
@Composable
fun TagChip(
    text: String,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    suggested: Boolean = false,
    count: Int? = null,
    onSelectedChange: ((Boolean) -> Unit)? = null,
) {
    val shape = RoundedCornerShape(8.dp)
    Row(
        modifier = modifier
            .clip(shape)
            .then(
                if (onSelectedChange != null) {
                    Modifier.toggleable(value = selected, role = Role.Checkbox, onValueChange = onSelectedChange)
                } else {
                    Modifier
                }
            )
            .background(if (selected) SynapseDim else Panel)
            .border(
                1.dp,
                when {
                    selected -> Synapse
                    suggested -> Synapse.copy(alpha = 0.5f)
                    else -> Edge
                },
                shape,
            )
            .padding(horizontal = 12.dp, vertical = 7.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (suggested && !selected) {
            Box(Modifier.size(5.dp).background(Synapse, CircleShape))
        }
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) Synapse else Ink,
        )
        if (count != null) {
            Text(
                count.toString(),
                style = TextStyle(fontFamily = Mono, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.6.sp),
                color = Muted,
            )
        }
    }
}

@Composable
fun NewTagChip(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Text(
        "+ new tag",
        style = MaterialTheme.typography.labelLarge,
        color = Synapse,
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .dashedBorder(Edge)
            .padding(start = 10.dp, end = 12.dp, top = 7.dp, bottom = 7.dp),
    )
}

/** A tag that doesn't exist yet but was read off the page; tapping it creates the tag. */
@Composable
fun CandidateTagChip(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = Synapse,
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClickLabel = "Add tag", onClick = onClick)
            .dashedBorder(Synapse)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    )
}
