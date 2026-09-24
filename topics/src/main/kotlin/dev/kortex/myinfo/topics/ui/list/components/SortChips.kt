package dev.kortex.myinfo.topics.ui.list.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.kortex.design.Edge
import dev.kortex.design.Muted
import dev.kortex.design.Panel
import dev.kortex.design.Synapse
import dev.kortex.design.SynapseDim
import dev.kortex.myinfo.topics.domain.model.TopicSort
import dev.kortex.myinfo.topics.ui.common.ChipStyle

@Composable
internal fun SortChips(
    selected: TopicSort,
    pinnedCount: Int,
    onSelect: (TopicSort) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SortChip("RECENT", selected == TopicSort.Recent) { onSelect(TopicSort.Recent) }
        SortChip("PINNED $pinnedCount", selected == TopicSort.Pinned) { onSelect(TopicSort.Pinned) }
        SortChip("A–Z", selected == TopicSort.Alphabetical) { onSelect(TopicSort.Alphabetical) }
    }
}

@Composable
private fun SortChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    Text(
        label,
        style = ChipStyle,
        color = if (selected) Synapse else Muted,
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .clip(shape)
            .background(if (selected) SynapseDim else Panel)
            .border(1.dp, if (selected) Synapse else Edge, shape)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    )
}
