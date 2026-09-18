package dev.kortex.myinfo.topics.ui.list

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.kortex.design.Edge
import dev.kortex.design.EdgeStrong
import dev.kortex.design.Ink
import dev.kortex.design.Muted
import dev.kortex.design.Panel
import dev.kortex.design.dashedBorder
import dev.kortex.myinfo.topics.domain.model.TopicSuggestion
import dev.kortex.myinfo.topics.ui.common.HeroBodyStyle
import dev.kortex.myinfo.topics.ui.common.HeroTitleStyle
import dev.kortex.myinfo.topics.ui.common.MetaStyle
import dev.kortex.myinfo.topics.ui.common.PrimaryButton
import dev.kortex.myinfo.topics.ui.common.RowShape
import dev.kortex.myinfo.topics.ui.common.RowTitleStyle
import dev.kortex.myinfo.topics.ui.common.TypeBadge

/**
 * Before the first topic (Figma: Topics 1g): what topics are for, topics suggested from saved
 * links, and a way to start empty.
 */
@Composable
internal fun TopicsFirstRun(
    suggestions: List<TopicSuggestion>,
    onAccept: (TopicSuggestion) -> Unit,
    onCreate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp)
                .padding(top = 34.dp, bottom = 24.dp),
        ) {
            Text("One topic, everything about it", style = HeroTitleStyle, color = Ink)
            Spacer(Modifier.height(11.dp))
            Text(
                "A trip, a parent's medical file, a job hunt. Links, videos, docs, bills and your own " +
                    "notes — kept together instead of scattered across apps.",
                style = HeroBodyStyle,
                color = Muted,
            )
            // Without suggestions the button below is the only way in, so the scratch row would repeat it.
            if (suggestions.isNotEmpty()) {
                Spacer(Modifier.height(26.dp))
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    suggestions.forEach { suggestion ->
                        SuggestionRow(suggestion, onClick = { onAccept(suggestion) })
                    }
                    Text(
                        "Start from scratch instead",
                        style = RowTitleStyle,
                        color = Muted,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RowShape)
                            .dashedBorder(EdgeStrong, cornerRadius = 12.dp)
                            .clickable(role = Role.Button, onClick = onCreate)
                            .padding(14.dp),
                    )
                }
            }
        }
        PrimaryButton(
            "Create your first topic",
            onClick = onCreate,
            modifier = Modifier.padding(start = 18.dp, end = 18.dp, bottom = 18.dp),
        )
    }
}

@Composable
private fun SuggestionRow(suggestion: TopicSuggestion, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RowShape)
            .background(Panel)
            .border(1.dp, Edge, RowShape)
            .clickable(onClickLabel = "Create this topic", role = Role.Button, onClick = onClick)
            .padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TypeBadge(suggestion.kind, size = 36.dp, cornerRadius = 9.dp)
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(suggestion.name, style = RowTitleStyle, color = Ink)
            Text("SUGGESTED · ${suggestion.links.size} SAVED LINKS MATCH", style = MetaStyle, color = Muted)
        }
    }
}
