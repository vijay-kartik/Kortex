package dev.kortex.links.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.HapticFeedbackConstants
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kortex.design.Alarm
import dev.kortex.design.Edge
import dev.kortex.design.Grotesk
import dev.kortex.design.R
import dev.kortex.design.Ink
import dev.kortex.design.KortexTheme
import dev.kortex.design.Muted
import dev.kortex.design.Panel
import dev.kortex.design.Synapse
import dev.kortex.design.SynapseDim
import dev.kortex.design.Void
import dev.kortex.design.anim.EmphasizedDecelerate
import dev.kortex.design.anim.StandardEasing
import dev.kortex.design.dashedBorder
import dev.kortex.links.ui.components.TagChip
import dev.kortex.links.data.LinkEntity
import dev.kortex.links.data.LinkWithTags
import dev.kortex.links.data.TagLinkCount
import kotlinx.coroutines.delay


@Preview
@Composable
fun LinksScreen(modifier: Modifier = Modifier, onCreateLink: () -> Unit = {}, viewModel: LinksViewModel = hiltViewModel()) {
    val uiState by viewModel.linksScreenUiState.collectAsStateWithLifecycle()
    // The field reads local state so typing never waits on the filtered list round-trip.
    var query by rememberSaveable { mutableStateOf("") }
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        // RootScreen's Scaffold already pads for the system bars; the default insets here would
        // add the status bar height again above the header (and the nav bar below the list).
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        floatingActionButton = {
            FloatingActionButton(
                onClick = onCreateLink,
                containerColor = Synapse,
                contentColor = Void,
            ) {
                Icon(Icons.Default.Add, contentDescription = "New Link")
            }
        }
    ) { innerPadding ->
        Box(
            modifier = modifier
                .padding(innerPadding)
                .fillMaxSize()
                // The nav bar is already padded by the parent, so the keyboard only adds what's above it.
                .consumeWindowInsets(WindowInsets.navigationBars)
                .imePadding(),
            contentAlignment = Alignment.Center
        ) {
            when (val state = uiState) {
                LinksScreenUiState.LoadingUiState -> Unit
                LinksScreenUiState.EmptyLinksUiState -> EmptyLinksScreen()
                is LinksScreenUiState.LinksUiState -> LinksWithSearchScreen(
                    state = state,
                    query = query,
                    onQueryChange = {
                        query = it
                        viewModel.onQueryChange(it)
                    },
                    onTagToggle = viewModel::toggleTag,
                    // The card's copy animation is the confirmation; Android 13+ adds its own on top.
                    onLinkClick = { link -> clipboard.setText(AnnotatedString(link.url)) },
                    onOpenLink = { link -> context.openLink(link.url) },
                    onShareLink = { link -> context.shareLink(link) },
                    onDeleteLink = { link -> viewModel.deleteLink(link.id) },
                    onUndoDelete = viewModel::undoDelete,
                )
            }
        }
    }
}

@Composable
fun EmptyLinksScreen() {
    Column(
        modifier = Modifier
            .padding(horizontal = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier
                .size(56.dp)
                .background(SynapseDim, RoundedCornerShape(16.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painterResource(R.drawable.ic_link),
                contentDescription = null,
                tint = Synapse,
                modifier = Modifier.size(28.dp)
            )
        }
        Text("No links yet", style = MaterialTheme.typography.titleMedium)
        Text(
            "Tap + to create your first link.",
            style = MaterialTheme.typography.bodySmall,
            color = Muted,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * Long-pressing a card opens its options tray (Figma: Links / Options): the card takes the
 * Synapse border, everything else dims, and the tray expands under it. Any tap outside the card,
 * or back, closes it. Delete swaps the card for an undo row until the undo window runs out.
 */
@Composable
fun LinksWithSearchScreen(
    state: LinksScreenUiState.LinksUiState,
    query: String,
    onQueryChange: (String) -> Unit,
    onTagToggle: (String) -> Unit,
    onLinkClick: (LinkEntity) -> Unit,
    onOpenLink: (LinkEntity) -> Unit,
    onShareLink: (LinkEntity) -> Unit,
    onDeleteLink: (LinkEntity) -> Unit,
    onUndoDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Ages only need minute precision; refresh them whenever the list itself changes.
    val nowMillis = remember(state.links) { System.currentTimeMillis() }
    // One card animates at a time: a tap anywhere restarts the animation on the tapped card.
    var copyTap by remember { mutableStateOf<CopyTap?>(null) }
    var searchExpanded by rememberSaveable { mutableStateOf(false) }

    var optionsLinkId by rememberSaveable { mutableStateOf<Long?>(null) }
    // The tray closes for good when its link is filtered out or deleted, rather than reappearing with it.
    val openLinkId = optionsLinkId?.takeIf { id ->
        id != state.pendingDeletion?.linkId && state.links.any { it.link.id == id }
    }
    LaunchedEffect(openLinkId) { if (openLinkId == null) optionsLinkId = null }
    val optionsOpen = openLinkId != null
    val currentOptionsOpen by rememberUpdatedState(optionsOpen)
    val tapOutside = remember { OptionsCardBounds() }

    // The pressed card dims its neighbours first; the header and tags follow as the tray expands.
    val chromeAlpha by animateFloatAsState(
        targetValue = if (optionsOpen) DIMMED_ALPHA else 1f,
        animationSpec = tween(TRAY_MS, delayMillis = if (optionsOpen) PRESS_MS else 0, easing = StandardEasing),
        label = "chrome dim",
    )

    Column(
        modifier
            .fillMaxSize()
            .onGloballyPositioned { tapOutside.screen = it }
            // Initial pass, so a tap outside the open card only closes the tray: it never presses
            // what's under it or scrolls the list.
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    if (!currentOptionsOpen || tapOutside.contains(down.position)) return@awaitEachGesture
                    down.consume()
                    optionsLinkId = null
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        event.changes.forEach { it.consume() }
                    } while (event.changes.any { it.pressed })
                }
            },
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 16.dp, end = 16.dp, top = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            LinksSearchHeader(
                linkCount = state.linkCount,
                tagCount = state.tags.size,
                query = query,
                onQueryChange = onQueryChange,
                expanded = searchExpanded,
                onExpandedChange = { searchExpanded = it },
                modifier = Modifier.graphicsLayer { alpha = chromeAlpha },
            )
            // Registered after the header's, so back closes the tray before it collapses search.
            BackHandler(enabled = optionsOpen) { optionsLinkId = null }
            if (state.tags.isNotEmpty()) {
                TagFilters(
                    tags = state.tags,
                    selectedTags = state.selectedTags,
                    onTagToggle = onTagToggle,
                    modifier = Modifier.graphicsLayer { alpha = chromeAlpha },
                )
            }
            LazyColumn(
                modifier = Modifier.weight(1f),
                // Extra bottom room so the FAB never covers the last card.
                contentPadding = PaddingValues(bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(state.links, key = { it.link.id }) { link ->
                    val linkId = link.link.id
                    val isOpen = linkId == openLinkId
                    val rowAlpha by animateFloatAsState(
                        targetValue = if (optionsOpen && !isOpen) DIMMED_ALPHA else 1f,
                        animationSpec = tween(PRESS_MS, easing = StandardEasing),
                        label = "row dim",
                    )
                    AnimatedContent(
                        targetState = state.pendingDeletion?.takeIf { it.linkId == linkId },
                        transitionSpec = { fadeIn(tween(SWAP_MS)) togetherWith fadeOut(tween(SWAP_MS)) },
                        contentKey = { it != null },
                        modifier = Modifier
                            .animateItem()
                            .graphicsLayer { alpha = rowAlpha },
                        label = "link row",
                    ) { deletion ->
                        if (deletion != null) {
                            DeletedLinkRow(link = link, deletion = deletion, onUndo = onUndoDelete)
                        } else {
                            val copyTick = copyTap?.takeIf { it.linkId == linkId }?.tick
                            LinkCard(
                                link = link,
                                nowMillis = nowMillis,
                                copyTick = copyTick,
                                optionsOpen = isOpen,
                                onCopy = {
                                    copyTap = CopyTap(linkId, tick = (copyTap?.tick ?: 0) + 1)
                                    onLinkClick(link.link)
                                },
                                // Clear once finished so a card scrolled back into view doesn't replay it.
                                onCopyFinished = { if (copyTap?.tick == copyTick) copyTap = null },
                                onLongPress = { optionsLinkId = linkId },
                                onOpen = {
                                    optionsLinkId = null
                                    onOpenLink(link.link)
                                },
                                onShare = {
                                    optionsLinkId = null
                                    onShareLink(link.link)
                                },
                                onDelete = {
                                    optionsLinkId = null
                                    onDeleteLink(link.link)
                                },
                                modifier = if (isOpen) Modifier.onGloballyPositioned { tapOutside.card = it } else Modifier,
                            )
                        }
                    }
                }
                if (state.links.isEmpty()) {
                    item {
                        Text(
                            "No links match.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Muted,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            }
        }
        Crossfade(
            targetState = optionsOpen,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, top = 8.dp, bottom = 30.dp),
            label = "hint",
        ) { open ->
            Text(
                if (open) "tap outside or back to close" else "tap to copy · long-press for options",
                style = HintStyle,
                color = Muted,
            )
        }
    }
}

/** Where the open card sits within the screen, so a tap anywhere else can close its tray. */
private class OptionsCardBounds {
    var screen: LayoutCoordinates? = null
    var card: LayoutCoordinates? = null

    /** [position] is in [screen] coordinates. Only the card's visible part counts. */
    fun contains(position: Offset): Boolean {
        val screen = screen?.takeIf { it.isAttached } ?: return false
        val card = card?.takeIf { it.isAttached } ?: return false
        return screen.localBoundingBoxOf(card).contains(position)
    }
}

@Composable
private fun TagFilters(
    tags: List<TagLinkCount>,
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

private data class CopyTap(val linkId: Long, val tick: Int)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LinkCard(
    link: LinkWithTags,
    nowMillis: Long,
    copyTick: Int?,
    optionsOpen: Boolean,
    onCopy: () -> Unit,
    onCopyFinished: () -> Unit,
    onLongPress: () -> Unit,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val copyAnimation = rememberLinkCopyAnimation(copyTick, onCopyFinished)
    val view = LocalView.current
    val currentOnCopy by rememberUpdatedState(onCopy)
    val currentOnLongPress by rememberUpdatedState(onLongPress)

    // Taps outside close the tray without scrolling, so make sure all of it is on screen.
    val bringIntoView = remember { BringIntoViewRequester() }
    LaunchedEffect(optionsOpen) {
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
            LinkCardThumbnail(link = link.link, copyAnimation = copyAnimation)
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
                        link.link.url,
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
                        if (link.tagNames.isNotEmpty()) {
                            withStyle(SpanStyle(color = Synapse)) { append(link.tagNames.joinToString(" · ").uppercase()) }
                            append(" · ")
                        }
                        append(relativeAge(link.link.createdAtMillis, nowMillis))
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
            LinkOptionsTray(onOpen = onOpen, onShare = onShare, onDelete = onDelete)
        }
    }
}

@Composable
private fun LinkOptionsTray(onOpen: () -> Unit, onShare: () -> Unit, onDelete: () -> Unit) {
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

/** Stands in for a deleted link until its undo window runs out; the bottom bar counts it down. */
@Composable
private fun DeletedLinkRow(link: LinkWithTags, deletion: PendingDeletion, onUndo: () -> Unit) {
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

/**
 * The page image when there is one and the user hasn't hidden it, else the link glyph. Copying
 * swaps the glyph for a check on Synapse as before; an image keeps showing, dimmed under the check.
 * An image that finishes downloading after the link was saved crossfades in over the glyph.
 */
@Composable
private fun LinkCardThumbnail(link: LinkEntity, copyAnimation: LinkCopyAnimation) {
    Crossfade(
        targetState = link.imagePath?.takeUnless { link.imageHidden },
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

private fun LinkWithTags.displayTitle(): String = link.title.ifBlank { linkDomain(link.url) ?: link.url }

private fun Context.openLink(url: String) {
    // Addresses can be saved without a scheme, which no browser would claim.
    val uri = Uri.parse(url).let { if (it.scheme == null) Uri.parse("https://$url") else it }
    try {
        startActivity(Intent(Intent.ACTION_VIEW, uri))
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(this, "No app can open this link", Toast.LENGTH_SHORT).show()
    }
}

private fun Context.shareLink(link: LinkEntity) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, link.url)
        if (link.title.isNotBlank()) putExtra(Intent.EXTRA_SUBJECT, link.title)
    }
    startActivity(Intent.createChooser(send, "Share link"))
}

private val COPY_BADGE_SIZE = 28.dp
private const val IMAGE_ARRIVAL_MS = 200

// ── Options tray (Figma: Links / Options · 1 Press, 2 Tray, 3 Deleted) ──

private const val DIMMED_ALPHA = 0.35f
/** Border and neighbour dim land first, so the press reads before the tray moves. */
private const val PRESS_MS = 120
private const val TRAY_MS = 250
private const val CLOSE_MS = 200
private const val SWAP_MS = 200
private val OPEN_BORDER_WIDTH = 1.5.dp
private val COUNTDOWN_HEIGHT = 2.dp
private val UndoShape = RoundedCornerShape(12.dp)

private val TrayLabelStyle = TextStyle(fontFamily = Grotesk, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 18.sp, letterSpacing = 0.2.sp)
private val DeletedTitleStyle = TextStyle(fontFamily = Grotesk, fontSize = 15.sp, lineHeight = 20.sp)
private val HintStyle = LinkMetaStyle.copy(letterSpacing = 0.6.sp)

/** Compact, uppercase age: NOW, 5M AGO, 3H AGO, 3D AGO, 2W AGO, 4MO AGO, 1Y AGO. */
private fun relativeAge(createdAtMillis: Long, nowMillis: Long): String {
    val minutes = (nowMillis - createdAtMillis).coerceAtLeast(0) / 60_000
    val hours = minutes / 60
    val days = hours / 24
    return when {
        minutes < 1 -> "NOW"
        hours < 1 -> "${minutes}M AGO"
        days < 1 -> "${hours}H AGO"
        days < 7 -> "${days}D AGO"
        days < 30 -> "${days / 7}W AGO"
        days < 365 -> "${days / 30}MO AGO"
        else -> "${days / 365}Y AGO"
    }
}

// ── Previews ──────────────────────────────────────────────────────

@Preview
@Composable
private fun LinksWithSearchPreview() {
    val now = System.currentTimeMillis()
    val day = 24 * 60 * 60 * 1000L
    KortexTheme {
        Box(Modifier.background(Void)) {
            LinksWithSearchScreen(
                state = LinksScreenUiState.LinksUiState(
                    links = listOf(
                        LinkWithTags(
                            LinkEntity(1, "https://curaahome.com/products/curaa-automatic-pepper-grinder", "Auto pepper grinder", now - 3 * day),
                            listOf("to buy"),
                        ),
                        LinkWithTags(LinkEntity(2, "https://www.google.com", "Google website", now - 14 * day), listOf("sample")),
                    ),
                    tags = listOf(TagLinkCount("sample", 1), TagLinkCount("ticket", 0), TagLinkCount("to buy", 0)),
                    selectedTags = emptySet(),
                    linkCount = 1,
                    pendingDeletion = PendingDeletion(1, startedAtMillis = now, deadlineMillis = now + 5_000),
                ),
                query = "",
                onQueryChange = {},
                onTagToggle = {},
                onLinkClick = {},
                onOpenLink = {},
                onShareLink = {},
                onDeleteLink = {},
                onUndoDelete = {},
            )
        }
    }
}
