package dev.kortex.myinfo.topics.ui.list

import android.view.HapticFeedbackConstants
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kortex.design.Alarm
import dev.kortex.design.Amber
import dev.kortex.design.Edge
import dev.kortex.design.EdgeStrong
import dev.kortex.design.Ink
import dev.kortex.design.Muted
import dev.kortex.design.Panel
import dev.kortex.design.R
import dev.kortex.design.Sunken
import dev.kortex.design.Synapse
import dev.kortex.design.anim.EmphasizedDecelerate
import dev.kortex.design.anim.StandardEasing
import dev.kortex.design.dashedBorder
import dev.kortex.myinfo.topics.domain.model.ItemType
import dev.kortex.myinfo.topics.domain.model.Progress
import dev.kortex.myinfo.topics.domain.model.TopicOverview
import dev.kortex.myinfo.topics.ui.common.BodyStyle
import dev.kortex.myinfo.topics.ui.common.CardShape
import dev.kortex.myinfo.topics.ui.common.CardTitleStyle
import dev.kortex.myinfo.topics.ui.common.LocalThumbnail
import dev.kortex.myinfo.topics.ui.common.MetaStyle
import dev.kortex.myinfo.topics.ui.common.TrayLabelStyle
import dev.kortex.myinfo.topics.ui.common.TypeBadge
import dev.kortex.myinfo.topics.ui.common.countsLabel
import dev.kortex.myinfo.topics.ui.common.formatMoney
import dev.kortex.myinfo.topics.ui.common.updatedLabel

/**
 * One topic in the list (Figma: Topics 1a). The body shows the most telling thing the topic has:
 * thumbnails, then reading progress, then file-type badges, else just its counts. Long-press opens
 * the options tray under the card, as on Links cards.
 */
@Composable
internal fun TopicCard(
    overview: TopicOverview,
    nowMillis: Long,
    optionsOpen: Boolean,
    onOpen: () -> Unit,
    onLongPress: () -> Unit,
    onTogglePin: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val view = LocalView.current
    val currentOnOpen by rememberUpdatedState(onOpen)
    val currentOnLongPress by rememberUpdatedState(onLongPress)
    val topic = overview.topic

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(Panel)
            .border(
                width = if (optionsOpen) OPEN_BORDER_WIDTH else 1.dp,
                color = when {
                    optionsOpen -> Synapse
                    topic.pinned -> EdgeStrong
                    else -> Edge
                },
                shape = CardShape,
            ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onLongPress = {
                            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                            currentOnLongPress()
                        },
                        onTap = { currentOnOpen() },
                    )
                }
                .semantics {
                    onClick(label = "Open topic") {
                        currentOnOpen()
                        true
                    }
                    onLongClick(label = "Topic options") {
                        currentOnLongPress()
                        true
                    }
                }
                .padding(15.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    topic.name,
                    style = CardTitleStyle,
                    color = Ink,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (topic.pinned) PinnedTag(Modifier.padding(start = 12.dp))
            }
            TopicCardBody(overview)
            TopicCardFooter(overview, nowMillis)
        }
        AnimatedVisibility(
            visible = optionsOpen,
            enter = expandVertically(tween(TRAY_MS, delayMillis = PRESS_MS, easing = EmphasizedDecelerate)) +
                fadeIn(tween(TRAY_MS, delayMillis = PRESS_MS)),
            exit = shrinkVertically(tween(CLOSE_MS, easing = StandardEasing)) + fadeOut(tween(CLOSE_MS)),
        ) {
            TopicOptionsTray(pinned = topic.pinned, onOpen = onOpen, onTogglePin = onTogglePin, onDelete = onDelete)
        }
    }
}

@Composable
private fun TopicCardBody(overview: TopicOverview) {
    val counts = if (overview.itemCount == 0) "Nothing in here yet" else countsLabel(overview.counts)
    val fileTypes = overview.counts.keys.filter { it in FileTypes }
    when {
        overview.previews.isNotEmpty() -> {
            PreviewStrip(overview.previews)
            Text(counts, style = BodyStyle, color = Muted)
        }
        overview.reading != null -> {
            ReadingBar(overview.reading)
            Text(counts, style = BodyStyle, color = Muted)
        }
        fileTypes.isNotEmpty() -> Row(
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            fileTypes.forEach { TypeBadge(it) }
            Text(counts, style = BodyStyle, color = Muted, modifier = Modifier.padding(start = 3.dp))
        }
        else -> Text(counts, style = BodyStyle, color = Muted)
    }
}

/** Up to three tiles; when there are more thumbnails, the last tile counts the rest. */
@Composable
private fun PreviewStrip(previews: List<String>) {
    val overflow = previews.size > PREVIEW_TILES
    val shown = if (overflow) previews.take(PREVIEW_TILES - 1) else previews
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        shown.forEach { path ->
            LocalThumbnail(path, previewTile())
        }
        if (overflow) {
            Box(previewTile().background(Sunken), contentAlignment = Alignment.Center) {
                Text("+${previews.size - shown.size}", style = MetaStyle.copy(letterSpacing = 0.sp), color = Muted)
            }
        }
        // Keep tiles a third wide even when there are fewer than three.
        repeat(PREVIEW_TILES - shown.size - if (overflow) 1 else 0) { Spacer(Modifier.weight(1f)) }
    }
}

private fun RowScope.previewTile() = Modifier
    .weight(1f)
    .height(PREVIEW_HEIGHT)
    .clip(RoundedCornerShape(8.dp))

@Composable
private fun ReadingBar(reading: Progress) {
    Row(
        modifier = Modifier.padding(top = 1.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .weight(1f)
                .height(5.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(Sunken),
        ) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(reading.done.toFloat() / reading.total)
                    .background(Synapse),
            )
        }
        Text("${reading.done}/${reading.total} READ", style = MetaStyle.copy(letterSpacing = 0.sp), color = Muted)
    }
}

@Composable
private fun TopicCardFooter(overview: TopicOverview, nowMillis: Long) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            updatedLabel(overview.topic.updatedAtMillis, nowMillis),
            style = MetaStyle,
            color = Muted,
            modifier = Modifier.weight(1f),
        )
        if (overview.billTotals.isNotEmpty()) {
            Text(
                overview.billTotals.joinToString(" · ") { formatMoney(it).uppercase() } + " IN BILLS",
                style = MetaStyle,
                color = Amber,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 12.dp),
            )
        }
    }
}

@Composable
private fun PinnedTag(modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(5.dp)
    Text(
        "PINNED",
        style = MetaStyle.copy(fontSize = 9.sp, letterSpacing = 0.9.sp),
        color = Synapse,
        modifier = modifier
            .border(1.dp, EdgeStrong, shape)
            .padding(horizontal = 7.dp, vertical = 4.dp),
    )
}

@Composable
private fun TopicOptionsTray(pinned: Boolean, onOpen: () -> Unit, onTogglePin: () -> Unit, onDelete: () -> Unit) {
    Column {
        HorizontalDivider(thickness = 1.dp, color = Edge)
        Row(
            Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min),
        ) {
            TrayAction(R.drawable.ic_open, "Open", iconTint = Synapse, labelColor = Ink, onClick = onOpen)
            VerticalDivider(thickness = 1.dp, color = Edge)
            TrayAction(R.drawable.ic_pin, if (pinned) "Unpin" else "Pin", iconTint = Synapse, labelColor = Ink, onClick = onTogglePin)
            VerticalDivider(thickness = 1.dp, color = Edge)
            TrayAction(R.drawable.ic_trash, "Delete", iconTint = Alarm, labelColor = Alarm, onClick = onDelete)
        }
    }
}

@Composable
private fun RowScope.TrayAction(
    @DrawableRes icon: Int,
    label: String,
    iconTint: Color,
    labelColor: Color,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .weight(1f)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = iconTint, modifier = Modifier.size(20.dp))
        Text(label, style = TrayLabelStyle, color = labelColor)
    }
}

/** Stands in for a deleted topic until its undo window runs out; the bottom bar counts it down. */
@Composable
internal fun DeletedTopicRow(name: String, deletion: PendingTopicDeletion, onUndo: () -> Unit) {
    val countdown = remember(deletion) {
        val window = (deletion.deadlineMillis - deletion.startedAtMillis).coerceAtLeast(1)
        Animatable(((deletion.deadlineMillis - System.currentTimeMillis()).toFloat() / window).coerceIn(0f, 1f))
    }
    LaunchedEffect(deletion) {
        val remainingMs = (deletion.deadlineMillis - System.currentTimeMillis()).coerceAtLeast(0)
        countdown.animateTo(0f, tween(remainingMs.toInt(), easing = LinearEasing))
    }
    val undoShape = RoundedCornerShape(12.dp)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(Panel)
            .dashedBorder(Alarm, cornerRadius = 16.dp)
            .drawBehind {
                val height = COUNTDOWN_HEIGHT.toPx()
                drawRect(
                    color = Alarm,
                    topLeft = Offset(0f, size.height - height),
                    size = Size(size.width * countdown.value, height),
                )
            }
            .padding(start = 16.dp, end = 14.dp, top = 14.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(painterResource(R.drawable.ic_trash), contentDescription = null, tint = Alarm, modifier = Modifier.size(20.dp))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text("DELETED", style = MetaStyle, color = Alarm)
            Text(name, style = CardTitleStyle.copy(fontSize = 15.sp), color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(
            "UNDO",
            style = MetaStyle,
            color = Synapse,
            modifier = Modifier
                .minimumInteractiveComponentSize()
                .semantics { contentDescription = "Undo delete" }
                .clip(undoShape)
                .border(1.dp, Synapse, undoShape)
                .clickable(role = Role.Button, onClick = onUndo)
                .padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

/** Types whose badges stand in for thumbnails on a card with none. */
private val FileTypes = setOf(ItemType.Doc, ItemType.Image, ItemType.Bill)

private const val PREVIEW_TILES = 3
private val PREVIEW_HEIGHT = 58.dp
private val COUNTDOWN_HEIGHT = 3.dp
private val OPEN_BORDER_WIDTH = 1.5.dp

// Same timing as the Links options tray.
internal const val PRESS_MS = 120
internal const val TRAY_MS = 250
private const val CLOSE_MS = 200
