package dev.kortex.myinfo.topics.ui.detail

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kortex.design.Alarm
import dev.kortex.design.Amber
import dev.kortex.design.Edge
import dev.kortex.design.InkSoft
import dev.kortex.design.Muted
import dev.kortex.design.Panel
import dev.kortex.design.Synapse
import dev.kortex.design.SynapseDim
import dev.kortex.myinfo.topics.domain.model.TopicSummary
import dev.kortex.myinfo.topics.ui.common.BodyStyle
import dev.kortex.myinfo.topics.ui.common.HeroBodyStyle
import dev.kortex.myinfo.topics.ui.common.MetaStyle
import dev.kortex.myinfo.topics.ui.common.ageLabel

/**
 * The agent's short read of the topic, above its feed (Figma: Topics 1b). The model is only
 * asked when the user taps: before the first summary the card offers one, and once the topic
 * changes under a summary it says so and offers a refresh rather than re-asking on its own.
 */
@Composable
internal fun SummaryCard(
    summary: TopicSummary?,
    stale: Boolean,
    summarizing: Boolean,
    error: String?,
    canSummarize: Boolean,
    nowMillis: Long,
    onSummarize: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(Panel)
            .border(1.dp, if (summarizing) Synapse else Edge, CardShape)
            .animateContentSize()
            .padding(horizontal = 15.dp, vertical = 13.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("AGENT SUMMARY", style = MetaStyle, color = Synapse, modifier = Modifier.weight(1f))
            summary?.takeUnless { summarizing }?.let {
                Text(ageLabel(it.generatedAtMillis, nowMillis), style = MetaStyle, color = Muted)
            }
        }

        if (summary != null) {
            Text(
                summary.text,
                style = HeroBodyStyle,
                color = InkSoft,
                // Dimmed while being rewritten or once the topic has moved on, so it reads as old.
                modifier = Modifier.alpha(if (summarizing || stale) DIMMED else 1f),
            )
        } else if (!summarizing && error == null) {
            Text("A short read on what's in here and what's still open.", style = BodyStyle, color = Muted)
        }

        when {
            summarizing -> Text(
                "READING THE TOPIC…",
                style = MetaStyle,
                color = Synapse,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            // An error is a sentence to read; a status is a label.
            error != null -> Footer(error, BodyStyle, Alarm, action = "Try again", enabled = canSummarize, onClick = onSummarize)
            summary == null -> Footer(null, MetaStyle, Muted, action = "Summarise", enabled = canSummarize, onClick = onSummarize)
            stale -> Footer("TOPIC CHANGED SINCE", MetaStyle, Amber, action = "Refresh", enabled = canSummarize, onClick = onSummarize)
        }
    }
}

/** A note on the left — why the card wants attention — and the action that settles it. */
@Composable
private fun Footer(
    message: String?,
    messageStyle: TextStyle,
    messageColor: Color,
    action: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (message != null) {
            Text(
                message,
                style = messageStyle,
                color = messageColor,
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 12.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite },
            )
        } else {
            Spacer(Modifier.weight(1f))
        }
        val shape = RoundedCornerShape(50)
        Text(
            action,
            style = BodyStyle.copy(fontSize = 13.sp),
            color = if (enabled) Synapse else Muted,
            modifier = Modifier
                .clip(shape)
                .background(if (enabled) SynapseDim else Panel)
                .border(1.dp, if (enabled) Synapse else Edge, shape)
                .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 7.dp),
        )
    }
}

private val CardShape = RoundedCornerShape(14.dp)
private const val DIMMED = 0.55f
