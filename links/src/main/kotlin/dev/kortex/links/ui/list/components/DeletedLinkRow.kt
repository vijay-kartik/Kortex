package dev.kortex.links.ui.list.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kortex.design.Alarm
import dev.kortex.design.Grotesk
import dev.kortex.design.Muted
import dev.kortex.design.Panel
import dev.kortex.design.R
import dev.kortex.design.Synapse
import dev.kortex.design.dashedBorder
import dev.kortex.links.domain.model.Link
import dev.kortex.links.ui.common.LinkCardShape
import dev.kortex.links.ui.common.LinkMetaStyle
import dev.kortex.links.ui.common.displayTitle
import dev.kortex.links.ui.list.PendingLinkDeletion

/** Stands in for a deleted link until its undo window runs out; the bottom bar counts it down. */
@Composable
internal fun DeletedLinkRow(link: Link, deletion: PendingLinkDeletion, onUndo: () -> Unit) {
    val countdown = remember(deletion) {
        val window = (deletion.deadlineMillis - deletion.startedAtMillis).coerceAtLeast(1)
        Animatable(((deletion.deadlineMillis - System.currentTimeMillis()).toFloat() / window).coerceIn(0f, 1f))
    }
    LaunchedEffect(deletion) {
        val remainingMs = (deletion.deadlineMillis - System.currentTimeMillis()).coerceAtLeast(0)
        countdown.animateTo(0f, tween(remainingMs.toInt(), easing = LinearEasing))
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(LinkCardShape)
            .background(Panel)
            .dashedBorder(Alarm, cornerRadius = 14.dp)
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
            Text("DELETED", style = LinkMetaStyle, color = Alarm)
            Text(
                link.displayTitle(),
                style = DeletedTitleStyle,
                color = Muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            "UNDO",
            style = LinkMetaStyle,
            color = Synapse,
            modifier = Modifier
                .clip(UndoShape)
                .border(1.dp, Synapse, UndoShape)
                .clickable(onClickLabel = "Undo delete", role = Role.Button, onClick = onUndo)
                .padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

private val COUNTDOWN_HEIGHT = 2.dp
private val UndoShape = RoundedCornerShape(12.dp)
private val DeletedTitleStyle = TextStyle(fontFamily = Grotesk, fontSize = 15.sp, lineHeight = 20.sp)
