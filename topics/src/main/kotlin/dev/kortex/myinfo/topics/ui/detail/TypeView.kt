package dev.kortex.myinfo.topics.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kortex.design.Edge
import dev.kortex.design.Ink
import dev.kortex.design.Muted
import dev.kortex.design.Panel
import dev.kortex.design.Synapse
import dev.kortex.design.SynapseDim
import dev.kortex.design.Void
import dev.kortex.myinfo.topics.domain.model.ItemType
import dev.kortex.myinfo.topics.domain.model.TopicItem
import dev.kortex.myinfo.topics.ui.common.BodyStyle
import dev.kortex.myinfo.topics.ui.common.ChipStyle
import dev.kortex.myinfo.topics.ui.common.HeroTitleStyle
import dev.kortex.myinfo.topics.ui.common.MetaStyle
import dev.kortex.myinfo.topics.ui.common.RowShape
import dev.kortex.myinfo.topics.ui.common.VideoThumbnail
import dev.kortex.myinfo.topics.ui.common.ageLabel
import dev.kortex.myinfo.topics.ui.common.formatDuration
import dev.kortex.myinfo.topics.ui.common.noun
import dev.kortex.myinfo.topics.ui.common.resume

// ── A type's own view (Figma: Topic videos 2b–2d) ─────────────────

/** "Videos" over "5 ITEMS · 1H 12M TOTAL · 39M LEFT TO WATCH". */
@Composable
internal fun TypeViewHeader(state: TopicDetailState, type: ItemType) {
    Column(verticalArrangement = Arrangement.spacedBy(7.dp), modifier = Modifier.padding(top = 5.dp)) {
        Text(
            type.noun(2).replaceFirstChar { it.uppercase() },
            style = HeroTitleStyle.copy(lineHeight = 29.sp),
            color = Ink,
            modifier = Modifier.semantics { heading() },
        )
        Text(typeMetaLine(state), style = MetaStyle.copy(letterSpacing = 1.2.sp), color = Muted)
    }
}

/**
 * ALL, the picked type, and its status chip. The status chip filters on its own, so it can be
 * turned off without leaving the type.
 */
@Composable
internal fun TypeChips(state: TopicDetailState, type: ItemType, onIntent: (TopicDetailIntent) -> Unit) {
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Row(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            Chip("ALL ${state.detail?.items?.size ?: 0}", on = false, role = Role.Tab) {
                onIntent(TopicDetailIntent.SelectFilter(null))
            }
            // Already showing it: picking it again changes nothing.
            Chip("${type.noun(2).uppercase()} ${state.typeItems.size}", on = true, role = Role.Tab) {}
        }
        state.statusFilter?.let { status ->
            Chip("${status.status.undone} ${status.count}", on = state.showsOnlyUndone, role = Role.Switch) {
                onIntent(TopicDetailIntent.ToggleOnlyUndone)
            }
        }
    }
}

@Composable
private fun Chip(label: String, on: Boolean, role: Role, onClick: () -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    val interaction = if (role == Role.Switch) {
        Modifier.toggleable(value = on, role = role, onValueChange = { onClick() })
    } else {
        Modifier.selectable(selected = on, role = role, onClick = onClick)
    }
    Text(
        label,
        style = ChipStyle,
        color = if (on) Synapse else Muted,
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .clip(shape)
            .background(if (on) SynapseDim else Panel)
            .border(1.dp, if (on) Synapse else Edge, shape)
            .then(interaction)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    )
}

/**
 * A video in its type's view: picture on the left, title and where it stands on the right
 * (Figma: Topic videos 2b, 2k). In selection mode a check circle leads the row.
 */
@Composable
internal fun VideoRow(
    video: TopicItem.Video,
    nowMillis: Long,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    selecting: Boolean,
    selected: Boolean,
    modifier: Modifier = Modifier,
) {
    val status = videoRowStatus(video, nowMillis)
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .clip(RowShape)
            .background(if (selected) SynapseDim else Void)
            .itemGestures(selecting, selected, clickLabel = if (video.playsInApp) "Play" else "Open in YouTube", onClick, onLongPress)
            // In selection mode every row is inset, picked or not, so the tint of a picked one has room
            // around the circle and the text, and rows don't shift as they're picked.
            .padding(horizontal = if (selecting) 10.dp else 0.dp, vertical = if (selecting) 8.dp else 2.dp),
    ) {
        if (selecting) SelectionCircle(selected)
        VideoThumbnail(video, Modifier.width(ROW_THUMB_WIDTH).aspectRatio(16f / 9f))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            if (video.pinned) {
                Text(
                    "PINNED",
                    style = MetaStyle.copy(fontSize = 9.sp),
                    color = Synapse,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(SynapseDim)
                        .padding(horizontal = 5.dp, vertical = 2.dp),
                )
            }
            Text(
                video.link.title.ifBlank { video.link.url },
                style = BodyStyle.copy(fontSize = 15.sp, lineHeight = 20.sp),
                color = Ink,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(status.text, style = MetaStyle.copy(letterSpacing = 0.8.sp), color = if (status.accent) Synapse else Muted)
        }
    }
}

/** The tick circle a row leads with in selection mode (Figma: Topic videos 2d); the row itself carries the state for TalkBack. */
@Composable
private fun SelectionCircle(selected: Boolean) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(22.dp)
            .clip(CircleShape)
            .then(if (selected) Modifier.background(Synapse) else Modifier.border(1.5.dp, Muted, CircleShape))
            .clearAndSetSemantics { },
    ) {
        if (selected) Text("✓", style = MetaStyle.copy(fontSize = 12.sp), color = Void)
    }
}

/** "2 watched videos hidden · SHOW", under a list the status chip has trimmed (Figma: Topic videos 2c). */
@Composable
internal fun HiddenDoneRow(state: TopicDetailState, type: ItemType, onShow: () -> Unit) {
    val count = state.hiddenDoneCount
    val status = state.statusFilter?.status ?: return
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Text("$count ${status.done} ${type.noun(count)} hidden", style = BodyStyle.copy(fontSize = 14.sp), color = Muted, modifier = Modifier.weight(1f))
        Text(
            "SHOW",
            style = MetaStyle,
            color = Synapse,
            modifier = Modifier
                .minimumInteractiveComponentSize()
                .clip(RoundedCornerShape(8.dp))
                .clickable(role = Role.Button, onClickLabel = "Show them", onClick = onShow)
                .padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}

/** Along the foot of a type's view: long-press is the way in to selection, SELECT the way for those who don't know it. */
@Composable
internal fun SelectHintBar(onSelect: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .navigationBarsPadding()
            .padding(horizontal = 18.dp, vertical = 10.dp)
            .fillMaxWidth()
            .clip(RowShape)
            .background(Panel)
            .border(1.dp, Edge, RowShape)
            .padding(start = 14.dp),
    ) {
        Text("Long-press to move, pin or delete", style = BodyStyle.copy(fontSize = 14.sp), color = Muted, modifier = Modifier.weight(1f))
        Text(
            "SELECT",
            style = MetaStyle,
            color = Synapse,
            modifier = Modifier
                .minimumInteractiveComponentSize()
                .clickable(role = Role.Button, onClickLabel = "Select items", onClick = onSelect)
                .padding(horizontal = 14.dp, vertical = 12.dp),
        )
    }
}

private val ROW_THUMB_WIDTH = 140.dp

// ── Text ───────────────────────────────────────────────────────────

/**
 * The line under a type's name. Every type counts its items; types with a done state count what's
 * left; videos add their running time and what's left to watch. With the status chip on it counts
 * only what's showing: "3 UNWATCHED · 39M LEFT TO WATCH".
 */
internal fun typeMetaLine(state: TopicDetailState): String {
    val items = state.typeItems
    val status = state.statusFilter
    val parts = mutableListOf<String>()
    when {
        status == null -> parts += "${items.size} ${if (items.size == 1) "ITEM" else "ITEMS"}"
        state.showsOnlyUndone -> parts += "${status.count} ${status.status.undone}"
        else -> {
            parts += "${items.size} ${if (items.size == 1) "ITEM" else "ITEMS"}"
            // Videos say how much is left in time instead, below.
            if (state.typeView != ItemType.Video && status.count > 0) parts += "${status.count} ${status.status.undone}"
        }
    }
    if (state.typeView == ItemType.Video) {
        val videos = items.filterIsInstance<TopicItem.Video>()
        val total = videos.sumOf { it.durationSeconds ?: 0 }
        if (!state.showsOnlyUndone && total > 0) parts += "${formatSpan(total)} TOTAL"
        val left = videos.filter { !it.watched }.sumOf { it.resume?.secondsLeft ?: it.durationSeconds ?: 0 }
        if (left > 0) parts += "${formatSpan(left)} LEFT TO WATCH"
    }
    return parts.joinToString(" · ")
}

/** "1H 12M", "39M", "45S": a running time at a glance. */
internal fun formatSpan(seconds: Int): String {
    val h = seconds / 3600
    val m = seconds % 3600 / 60
    return when {
        h > 0 && m > 0 -> "${h}H ${m}M"
        h > 0 -> "${h}H"
        m > 0 -> "${m}M"
        else -> "${seconds}S"
    }
}

/** What a video row says under its title, and whether it's in the accent (still to watch) or muted (done). */
internal data class RowStatus(val text: String, val accent: Boolean)

internal fun videoRowStatus(video: TopicItem.Video, nowMillis: Long): RowStatus {
    val age = ageLabel(video.addedAtMillis, nowMillis)
    val resume = video.resume
    return when {
        video.embedBlocked -> RowStatus("OPENS YOUTUBE ↗ · $age", accent = true)
        video.watched -> RowStatus("✓ WATCHED · $age", accent = false)
        resume != null -> RowStatus("${formatDuration(resume.secondsLeft)} LEFT · $age", accent = true)
        else -> RowStatus("UNWATCHED · $age", accent = true)
    }
}
