package dev.kortex.links.ui.list.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.kortex.links.domain.model.TagCount
import dev.kortex.links.ui.components.TagChip

/** One chip per tag with its link count; selected tags narrow the list. */
@Composable
internal fun TagFilters(
    tags: List<TagCount>,
    selectedTags: Set<String>,
    onTagToggle: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(bottom = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        tags.forEach { tag ->
            TagChip(
                text = tag.name,
                selected = tag.name in selectedTags,
                count = tag.linkCount,
                onSelectedChange = { onTagToggle(tag.name) },
            )
        }
    }
}
