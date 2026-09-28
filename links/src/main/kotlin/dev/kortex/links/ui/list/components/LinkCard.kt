package dev.kortex.links.ui.list.components

import android.view.HapticFeedbackConstants
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kortex.design.Alarm
import dev.kortex.design.Edge
import dev.kortex.design.Grotesk
import dev.kortex.design.Ink
import dev.kortex.design.Muted
import dev.kortex.design.Panel
import dev.kortex.design.R
import dev.kortex.design.Synapse
import dev.kortex.design.Void
import dev.kortex.design.anim.EmphasizedDecelerate
import dev.kortex.design.anim.StandardEasing
import dev.kortex.links.domain.model.Link
import dev.kortex.links.ui.LinkCardInset
import dev.kortex.links.ui.LinkCardShape
import dev.kortex.links.ui.LinkCopyAnimation
import dev.kortex.links.ui.LinkMetaStyle
import dev.kortex.links.ui.LinkThumbnailShape
import dev.kortex.links.ui.LinkThumbnailSize
import dev.kortex.links.ui.LinkTitleStyle
import dev.kortex.links.ui.LinkUrlStyle
import dev.kortex.links.ui.ThumbnailImage
import dev.kortex.links.ui.common.displayTitle
import dev.kortex.links.ui.common.relativeAge
import dev.kortex.links.ui.rememberLinkCopyAnimation
import kotlinx.coroutines.delay

/**
 * A saved link. Tap copies it, long-press opens its options tray under it (Figma: Links /
 * Options); the tray swaps for [tagEditor] while [editingTags].
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun LinkCard(
    link: Link,
    nowMillis: Long,
    copyTick: Int?,
    optionsOpen: Boolean,
    /** Shows [tagEditor] in place of the options tray. */
    editingTags: Boolean,
    onCopy: () -> Unit,
    onCopyFinished: () -> Unit,
    onLongPress: () -> Unit,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    onEditTags: () -> Unit,
    tagEditor: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val copyAnimation = rememberLinkCopyAnimation(copyTick, onCopyFinished)
    val view = LocalView.current
    val currentOnCopy by rememberUpdatedState(onCopy)
    val currentOnLongPress by rememberUpdatedState(onLongPress)
    // While the tray collapses, it keeps showing whichever panel was open.
    val trayShowsEditor = remember { mutableStateOf(editingTags) }.apply { if (optionsOpen) value = editingTags }.value

    // Taps outside close the tray without scrolling, so make sure all of it is on screen.
    val bringIntoView = remember { BringIntoViewRequester() }
    LaunchedEffect(optionsOpen, editingTags) {
        if (optionsOpen) {
            delay((PRESS_MS + TRAY_MS).toLong())
            bringIntoView.bringIntoView()
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .bringIntoViewRequester(bringIntoView)
            .clip(LinkCardShape)
            .background(Panel)
            .border(
                width = if (optionsOpen) OPEN_BORDER_WIDTH else 1.dp,
                color = if (optionsOpen) Synapse else copyAnimation.borderColor,
                shape = LinkCardShape,
            ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // Raw tap detection instead of clickable: the haptic lands on touch-down, not release,
                // and there's no ripple because the card's fill must never change.
                .pointerInput(Unit) {
                    detectTapGestures(
                        onPress = { view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK) },
                        onLongPress = {
                            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                            currentOnLongPress()
                        },
                        onTap = { currentOnCopy() },
                    )
                }
                .semantics {
                    onClick(label = "Copy link") {
                        currentOnCopy()
                        true
                    }
                    onLongClick(label = "Link options") {
                        currentOnLongPress()
                        true
                    }
                }
                .drawBehind {
                    val thumbnailCenter = (LinkCardInset + LinkThumbnailSize / 2).toPx()
                    with(copyAnimation) { drawEffects(Offset(thumbnailCenter, thumbnailCenter)) }
                }
                .padding(LinkCardInset),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            LinkCardThumbnail(link = link, copyAnimation = copyAnimation)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    link.displayTitle(),
                    style = LinkTitleStyle,
                    color = Ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Box(Modifier.fillMaxWidth()) {
                    Text(
                        link.url,
                        style = LinkUrlStyle,
                        color = Muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.graphicsLayer { alpha = 1f - copyAnimation.urlLine },
                    )
                    Text(
                        "→ clipboard",
                        style = LinkUrlStyle,
                        color = Synapse,
                        maxLines = 1,
                        modifier = Modifier
                            .graphicsLayer { alpha = copyAnimation.urlLine }
                            .clearAndSetSemantics {},
                    )
                }
                Text(
                    buildAnnotatedString {
                        if (link.tags.isNotEmpty()) {
                            withStyle(SpanStyle(color = Synapse)) { append(link.tags.joinToString(" · ").uppercase()) }
                            append(" · ")
                        }
                        append(relativeAge(link.createdAtMillis, nowMillis))
                    },
                    style = LinkMetaStyle,
                    color = Muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        AnimatedVisibility(
            visible = optionsOpen,
            enter = expandVertically(tween(TRAY_MS, delayMillis = PRESS_MS, easing = EmphasizedDecelerate)) +
                fadeIn(tween(TRAY_MS, delayMillis = PRESS_MS)),
            exit = shrinkVertically(tween(CLOSE_MS, easing = StandardEasing)) + fadeOut(tween(CLOSE_MS)),
        ) {
            AnimatedContent(
                targetState = trayShowsEditor,
                transitionSpec = {
                    fadeIn(tween(TRAY_MS, delayMillis = SWAP_MS / 2)) togetherWith fadeOut(tween(SWAP_MS / 2)) using
                        SizeTransform { _, _ -> tween(TRAY_MS, easing = EmphasizedDecelerate) }
                },
                label = "tray panel",
            ) { showsEditor ->
                if (showsEditor) {
                    tagEditor()
                } else {
                    LinkOptionsTray(onOpen = onOpen, onShare = onShare, onEditTags = onEditTags, onDelete = onDelete)
                }
            }
        }
    }
}

@Composable
private fun LinkOptionsTray(onOpen: () -> Unit, onShare: () -> Unit, onEditTags: () -> Unit, onDelete: () -> Unit) {
    Column {
        HorizontalDivider(thickness = 1.dp, color = Edge)
        Row(
            Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min),
        ) {
            TrayAction(R.drawable.ic_open, "Open", iconTint = Synapse, labelColor = Ink, onClick = onOpen)
            VerticalDivider(thickness = 1.dp, color = Edge)
            TrayAction(R.drawable.ic_share_nodes, "Share", iconTint = Synapse, labelColor = Ink, onClick = onShare)
            VerticalDivider(thickness = 1.dp, color = Edge)
            TrayAction(R.drawable.ic_tag, "Tags", iconTint = Synapse, labelColor = Ink, onClick = onEditTags)
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

/**
 * The page image when there is one and the user hasn't hidden it, else the link glyph. Copying
 * swaps the glyph for a check on Synapse as before; an image keeps showing, dimmed under the check.
 * An image that finishes downloading after the link was saved crossfades in over the glyph.
 */
@Composable
private fun LinkCardThumbnail(link: Link, copyAnimation: LinkCopyAnimation) {
    Crossfade(
        targetState = link.thumbnailPath,
        animationSpec = tween(IMAGE_ARRIVAL_MS),
        modifier = Modifier
            .size(LinkThumbnailSize)
            .clip(LinkThumbnailShape),
        label = "card thumbnail",
    ) { imagePath ->
        if (imagePath != null) {
            Box(contentAlignment = Alignment.Center) {
                ThumbnailImage(imagePath)
                Box(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer { alpha = copyAnimation.glyph }
                        .background(Void.copy(alpha = 0.55f)),
                )
                Box(
                    Modifier
                        .size(COPY_BADGE_SIZE)
                        .graphicsLayer { alpha = copyAnimation.glyph }
                        .background(Synapse, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(painterResource(R.drawable.ic_check), contentDescription = null, tint = Void, modifier = Modifier.size(COPY_BADGE_SIZE))
                }
            }
        } else {
            // Both icons are stacked and centred, so the link → check swap is pure opacity.
            Box(
                Modifier
                    .fillMaxSize()
                    .drawBehind { drawRect(copyAnimation.glyphFill) },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painterResource(R.drawable.ic_link),
                    contentDescription = null,
                    tint = Synapse,
                    modifier = Modifier
                        .size(26.dp)
                        .graphicsLayer { alpha = 1f - copyAnimation.glyph },
                )
                Icon(
                    painterResource(R.drawable.ic_check),
                    contentDescription = null,
                    tint = Void,
                    // The tick is drawn on a 36dp grid.
                    modifier = Modifier
                        .size(36.dp)
                        .graphicsLayer { alpha = copyAnimation.glyph },
                )
            }
        }
    }
}

private val COPY_BADGE_SIZE = 28.dp
private const val IMAGE_ARRIVAL_MS = 200
private val OPEN_BORDER_WIDTH = 1.5.dp
private val TrayLabelStyle = TextStyle(fontFamily = Grotesk, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 18.sp, letterSpacing = 0.2.sp)
