package dev.kortex.myinfo.topics.ui.detail

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kortex.design.Alarm
import dev.kortex.design.Edge
import dev.kortex.design.EdgeStrong
import dev.kortex.design.Ink
import dev.kortex.design.InkSoft
import dev.kortex.design.KortexTheme
import dev.kortex.design.Muted
import dev.kortex.design.Panel
import dev.kortex.design.R
import dev.kortex.design.Synapse
import dev.kortex.design.SynapseDim
import dev.kortex.design.Void
import dev.kortex.design.Well
import dev.kortex.design.anim.EmphasizedDecelerate
import dev.kortex.design.anim.StandardEasing
import dev.kortex.design.dashedBorder
import dev.kortex.mvi.ObserveEffects
import dev.kortex.mvi.ScopedViewModelStore
import dev.kortex.myinfo.topics.domain.model.FeedGroup
import dev.kortex.myinfo.topics.domain.model.ItemType
import dev.kortex.myinfo.topics.domain.model.SavedEmail
import dev.kortex.myinfo.topics.domain.model.SavedLink
import dev.kortex.myinfo.topics.domain.model.TimePeriod
import dev.kortex.myinfo.topics.domain.model.Topic
import dev.kortex.myinfo.topics.domain.model.TopicDetail
import dev.kortex.myinfo.topics.domain.model.TopicItem
import dev.kortex.myinfo.topics.domain.model.TopicViewMode
import dev.kortex.myinfo.topics.domain.model.storedFile
import dev.kortex.myinfo.topics.ui.capture.QuickCaptureSheet
import dev.kortex.myinfo.topics.ui.common.BodyStyle
import dev.kortex.myinfo.topics.ui.common.ButtonStyle
import dev.kortex.myinfo.topics.ui.common.ChipStyle
import dev.kortex.myinfo.topics.ui.common.HeroTitleStyle
import dev.kortex.myinfo.topics.ui.common.MetaStyle
import dev.kortex.myinfo.topics.ui.common.TopicChoice
import dev.kortex.myinfo.topics.ui.common.TrayLabelStyle
import dev.kortex.myinfo.topics.ui.common.noun
import dev.kortex.myinfo.topics.ui.common.openFile
import dev.kortex.myinfo.topics.ui.common.updatedLabel
import dev.kortex.myinfo.topics.ui.email.EmailReaderRoute
import kotlinx.coroutines.launch

/**
 * Full-screen topic detail, with quick capture for adding to it. [onClose] leaves, and is also
 * called when the topic is deleted. Each topic gets its own ViewModel.
 */
@Composable
fun TopicDetailRoute(
    topicId: Long,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ScopedViewModelStore(key = "topic-$topicId") {
        val viewModel = hiltViewModel<TopicDetailViewModel, TopicDetailViewModel.Factory>(
            creationCallback = { factory -> factory.create(topicId) },
        )
        TopicDetailContent(viewModel, onClose, modifier)
    }
}

@Composable
private fun TopicDetailContent(
    viewModel: TopicDetailViewModel,
    onClose: () -> Unit,
    modifier: Modifier,
) {
    BackHandler(onBack = onClose)
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbars = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    ObserveEffects(viewModel.effects) { effect ->
        when (effect) {
            is TopicDetailEffect.OpenUrl -> context.openUrl(effect.url)
            is TopicDetailEffect.OpenFile -> if (!context.openFile(effect.file)) {
                scope.launch { snackbars.showSnackbar("No app on this phone opens that file.") }
            }
            is TopicDetailEffect.ShareText -> context.shareText(effect.subject, effect.text)
            is TopicDetailEffect.CopyText -> {
                clipboard.setText(AnnotatedString(effect.text))
                // Android 13 and later confirm a copy themselves; before that, say so here.
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                    scope.launch { snackbars.showSnackbar("Note copied") }
                }
            }
            // Launched, so a snackbar on screen doesn't hold up the effects behind it.
            is TopicDetailEffect.ShowMessage -> scope.launch { snackbars.showSnackbar(effect.text) }
            TopicDetailEffect.Close -> onClose()
        }
    }
    Box(modifier.fillMaxSize()) {
        TopicDetailScreen(state = state, onIntent = viewModel::onIntent, onBack = onClose, snackbars = snackbars)
        EmailReaderLayer(state.reading, onClose = { viewModel.onIntent(TopicDetailIntent.CloseEmail) })
    }

    val topicId = state.detail?.topic?.id
    if (state.capturing && topicId != null) {
        QuickCaptureSheet(
            topicId = topicId,
            onDismiss = { viewModel.onIntent(TopicDetailIntent.CloseCapture) },
            onSaved = { id, name -> viewModel.onIntent(TopicDetailIntent.Captured(id, name)) },
        )
    }
}

/**
 * The email reader, pushed over the feed the way the Topics screens push in, and holding the last
 * email while it slides away so it doesn't blank out on the way.
 */
@Composable
private fun EmailReaderLayer(email: SavedEmail?, onClose: () -> Unit) {
    var shown by remember { mutableStateOf(email) }
    SideEffect { if (email != null) shown = email }
    AnimatedVisibility(
        visible = email != null,
        enter = slideInHorizontally(tween(READER_ENTER_MS, easing = EmphasizedDecelerate)) { it / READER_SLIDE_FRACTION } +
            fadeIn(tween(READER_ENTER_MS, easing = EmphasizedDecelerate)),
        exit = slideOutHorizontally(tween(READER_EXIT_MS, easing = StandardEasing)) { it / READER_SLIDE_FRACTION } +
            fadeOut(tween(READER_EXIT_MS, easing = StandardEasing)),
        label = "email reader",
    ) {
        (email ?: shown)?.let { EmailReaderRoute(it, onClose = onClose) }
    }
}

private const val READER_ENTER_MS = 300
private const val READER_EXIT_MS = 200

/** A nudge rather than a full-width slide, like the other Topics screens. */
private const val READER_SLIDE_FRACTION = 8

@Composable
fun TopicDetailScreen(
    state: TopicDetailState,
    onIntent: (TopicDetailIntent) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    snackbars: SnackbarHostState = remember { SnackbarHostState() },
) {
    val detail = state.detail
    // Selection takes over the screen: its own bar, its own actions, and back gets out of it.
    BackHandler(enabled = state.selecting) { onIntent(TopicDetailIntent.ClearSelection) }

    // The selection bars keep showing the last selection while they animate away; read live, they'd
    // flash "0 selected" and flip Pin to Unpin on their way out.
    var lastSelecting by remember { mutableStateOf(state) }
    SideEffect { if (state.selecting) lastSelecting = state }
    val bars = if (state.selecting) state else lastSelecting

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbars) },
        topBar = {
            AnimatedContent(
                targetState = state.selecting,
                transitionSpec = { fadeIn(tween(BAR_ENTER_MS, easing = StandardEasing)) togetherWith fadeOut(tween(BAR_EXIT_MS)) },
                label = "top bar",
            ) { selecting ->
                if (selecting) {
                    SelectionTopBar(
                        count = bars.selection.size,
                        allSelected = bars.selection.size == bars.items.size,
                        onClear = { onIntent(TopicDetailIntent.ClearSelection) },
                        onSelectAll = { onIntent(TopicDetailIntent.SelectAll) },
                    )
                } else {
                    DetailTopBar(
                        pinned = detail?.topic?.pinned == true,
                        menuEnabled = detail != null,
                        onBack = onBack,
                        onSetPinned = { onIntent(TopicDetailIntent.SetPinned(it)) },
                        onDelete = { onIntent(TopicDetailIntent.AskDelete) },
                    )
                }
            }
        },
        bottomBar = {
            AnimatedVisibility(
                visible = state.selecting,
                enter = slideInVertically(tween(BAR_ENTER_MS, easing = EmphasizedDecelerate)) { it } + fadeIn(tween(BAR_ENTER_MS)),
                exit = slideOutVertically(tween(BAR_EXIT_MS, easing = StandardEasing)) { it } + fadeOut(tween(BAR_EXIT_MS)),
                label = "selection actions",
            ) {
                SelectionActionBar(
                    pinned = bars.selectionPinned,
                    canMove = bars.canMoveSelection,
                    onPin = { onIntent(TopicDetailIntent.PinSelection) },
                    onMove = { onIntent(TopicDetailIntent.AskMoveSelection) },
                    onDelete = { onIntent(TopicDetailIntent.AskDeleteSelection) },
                )
            }
        },
        floatingActionButton = {
            AnimatedVisibility(
                visible = detail != null && detail.items.isNotEmpty() && !state.selecting,
                enter = scaleIn(tween(BAR_ENTER_MS, easing = EmphasizedDecelerate)) + fadeIn(tween(BAR_ENTER_MS)),
                exit = scaleOut(tween(BAR_EXIT_MS, easing = StandardEasing)) + fadeOut(tween(BAR_EXIT_MS)),
                label = "add button",
            ) {
                FloatingActionButton(
                    onClick = { onIntent(TopicDetailIntent.Add) },
                    shape = RoundedCornerShape(14.dp),
                    containerColor = Synapse,
                    contentColor = Void,
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Add to topic")
                }
            }
        },
    ) { innerPadding ->
        if (detail != null) {
            DetailFeed(state, detail, onIntent, Modifier.padding(innerPadding))
        }
    }

    if (state.movingSelection) {
        MoveToTopicSheet(
            targets = state.moveTargets,
            count = state.selection.size,
            onPick = { onIntent(TopicDetailIntent.MoveSelectionTo(it)) },
            onDismiss = { onIntent(TopicDetailIntent.CancelMoveSelection) },
        )
    }

    if (state.confirmingSelectionDelete) {
        val count = state.selection.size
        AlertDialog(
            onDismissRequest = { onIntent(TopicDetailIntent.CancelDeleteSelection) },
            title = { Text(if (count == 1) "Delete this item?" else "Delete $count items?") },
            text = { Text("Any files they keep go with them. Links stay in your Links tab.") },
            confirmButton = {
                TextButton(onClick = { onIntent(TopicDetailIntent.ConfirmDeleteSelection) }) { Text("Delete", color = Alarm) }
            },
            dismissButton = {
                TextButton(onClick = { onIntent(TopicDetailIntent.CancelDeleteSelection) }) { Text("Cancel", color = Muted) }
            },
            containerColor = Panel,
        )
    }

    if (state.confirmingDelete && detail != null) {
        AlertDialog(
            onDismissRequest = { onIntent(TopicDetailIntent.CancelDelete) },
            title = { Text("Delete “${detail.topic.name}”?") },
            text = { Text("Its notes go with it. Links stay in your Links tab.") },
            confirmButton = {
                TextButton(onClick = { onIntent(TopicDetailIntent.ConfirmDelete) }) { Text("Delete", color = Alarm) }
            },
            dismissButton = {
                TextButton(onClick = { onIntent(TopicDetailIntent.CancelDelete) }) { Text("Cancel", color = Muted) }
            },
            containerColor = Panel,
        )
    }
}

@Composable
private fun DetailFeed(
    state: TopicDetailState,
    detail: TopicDetail,
    onIntent: (TopicDetailIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Stamped by the ViewModel with the feed, so its groups and ages agree with each other.
    val nowMillis = state.nowMillis
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        // Extra bottom room so the FAB never covers the last card.
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "header") {
            Column(verticalArrangement = Arrangement.spacedBy(7.dp), modifier = Modifier.padding(top = 5.dp)) {
                Text(
                    detail.topic.name,
                    style = HeroTitleStyle.copy(lineHeight = 29.sp),
                    color = Ink,
                    modifier = Modifier.semantics { heading() },
                )
                Text(metaLine(detail, nowMillis), style = MetaStyle.copy(letterSpacing = 1.2.sp), color = Muted)
            }
        }
        item(key = "actions") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 2.dp)) {
                ActionButton("+ Add", primary = true, onClick = { onIntent(TopicDetailIntent.Add) }, modifier = Modifier.weight(1f))
                ActionButton("Share", primary = false, onClick = { onIntent(TopicDetailIntent.Share) }, modifier = Modifier.weight(1f))
            }
        }
        if (state.showSummaryCard) {
            item(key = "summary") {
                SummaryCard(
                    summary = state.summary,
                    stale = state.summaryStale,
                    summarizing = state.summarizing,
                    error = state.summaryError,
                    canSummarize = state.canSummarize,
                    nowMillis = nowMillis,
                    onSummarize = { onIntent(TopicDetailIntent.Summarize) },
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        if (detail.items.isEmpty()) {
            item(key = "empty") { EmptyTopic(onAdd = { onIntent(TopicDetailIntent.Add) }) }
        } else {
            detail.bills?.let { bills ->
                item(key = "bills") { BillsCard(bills, nowMillis, Modifier.padding(top = 2.dp)) }
            }
            item(key = "modes") {
                ViewModeSwitch(state.mode, onSelect = { onIntent(TopicDetailIntent.SelectMode(it)) })
            }
            item(key = "filters") {
                FilterChips(state, onSelect = { onIntent(TopicDetailIntent.SelectFilter(it)) })
            }
            state.sections.forEach { section ->
                // Feed is one unbroken run of cards, so it gets no heading at all.
                if (section.group != FeedGroup.Everything) {
                    item(key = "group-${groupKey(section.group)}") {
                        GroupHeading(groupLabel(section.group), section.items.size)
                    }
                }
                items(section.items, key = { it.id }) { item ->
                    TopicItemCard(
                        item = item,
                        nowMillis = nowMillis,
                        onClick = if (item.opens()) ({ onIntent(TopicDetailIntent.OpenItem(item)) }) else null,
                        onSetDone = { done -> onIntent(TopicDetailIntent.SetItemDone(item, done)) },
                        clickLabel = if (item is TopicItem.Note) "Copy note" else "Open item",
                        onLongPress = {
                            if (state.selecting) {
                                onIntent(TopicDetailIntent.ToggleSelection(item.id))
                            } else {
                                onIntent(TopicDetailIntent.StartSelection(item.id))
                            }
                        },
                        selecting = state.selecting,
                        selected = item.id in state.selection,
                        modifier = Modifier.animateItem(),
                    )
                }
            }
            if (state.items.isEmpty()) {
                item(key = "none") {
                    val type = state.activeFilter
                    Text(
                        if (type == null) "Nothing here." else "No ${type.noun(2)} yet.",
                        style = BodyStyle,
                        color = Muted,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}

/**
 * Feed / Timeline / By type (Figma: Topics 1c). A 48dp track of 44dp segments: Compose widens a
 * touch to 48dp within the track, so every segment is a full-size target without the switch
 * growing past the chips around it.
 */
@Composable
private fun ViewModeSwitch(mode: TopicViewMode, onSelect: (TopicViewMode) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Well)
            .border(1.dp, Edge, RoundedCornerShape(10.dp))
            .padding(2.dp)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        TopicViewMode.entries.forEach { entry ->
            val isSelected = entry == mode
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = SEGMENT_HEIGHT)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (isSelected) SynapseDim else Color.Transparent)
                    .selectable(selected = isSelected, role = Role.Tab, onClick = { onSelect(entry) }),
            ) {
                Text(
                    modeLabel(entry),
                    style = ChipStyle,
                    color = if (isSelected) Synapse else Muted,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/** "TODAY · 4" over the run of cards it gathers; read as one heading, so TalkBack can jump between them. */
@Composable
private fun GroupHeading(label: String, count: Int) {
    Row(
        modifier = Modifier
            .padding(top = 6.dp)
            .semantics(mergeDescendants = true) { heading() },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(label, style = MetaStyle.copy(letterSpacing = 1.4.sp), color = InkSoft)
        HorizontalDivider(thickness = 1.dp, color = Edge, modifier = Modifier.weight(1f))
        Text("$count", style = MetaStyle, color = Muted)
    }
}

/** The bar that replaces the top bar while items are picked out (Figma: Topics 1e). */
@Composable
private fun SelectionTopBar(count: Int, allSelected: Boolean, onClear: () -> Unit, onSelectAll: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .background(Panel)
            .statusBarsPadding()
            .padding(horizontal = 6.dp, vertical = 4.dp),
    ) {
        BarGlyph("✕", "Leave selection", onClear, Modifier.align(Alignment.CenterStart))
        Text(
            "$count SELECTED",
            style = MetaStyle.copy(letterSpacing = 1.2.sp),
            color = Synapse,
            modifier = Modifier
                .align(Alignment.Center)
                // Read out as the count changes, so picking by touch alone is followable.
                .semantics {
                    contentDescription = if (count == 1) "1 item selected" else "$count items selected"
                    liveRegion = LiveRegionMode.Polite
                },
        )
        if (!allSelected) {
            Text(
                "ALL",
                style = MetaStyle.copy(letterSpacing = 1.2.sp),
                color = Muted,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .minimumInteractiveComponentSize()
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(onClickLabel = "Select every item", role = Role.Button, onClick = onSelectAll)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
    }
}

/** Move, Pin and Delete for the selection, along the bottom (Figma: Topics 1e). */
@Composable
private fun SelectionActionBar(
    pinned: Boolean,
    canMove: Boolean,
    onPin: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(Modifier.background(Panel)) {
        HorizontalDivider(thickness = 1.dp, color = Edge)
        Row(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .height(IntrinsicSize.Min),
        ) {
            SelectionAction(R.drawable.ic_open, "Move", Synapse, Ink, enabled = canMove, onClick = onMove)
            VerticalDivider(thickness = 1.dp, color = Edge)
            SelectionAction(R.drawable.ic_pin, if (pinned) "Unpin" else "Pin", Synapse, Ink, onClick = onPin)
            VerticalDivider(thickness = 1.dp, color = Edge)
            SelectionAction(R.drawable.ic_trash, "Delete", Alarm, Alarm, onClick = onDelete)
        }
    }
}

@Composable
private fun RowScope.SelectionAction(
    @DrawableRes icon: Int,
    label: String,
    iconTint: Color,
    labelColor: Color,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .weight(1f)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(vertical = 13.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        val alpha = if (enabled) 1f else DISABLED_ALPHA
        Icon(painterResource(icon), contentDescription = null, tint = iconTint.copy(alpha = alpha), modifier = Modifier.size(20.dp))
        Text(label, style = TrayLabelStyle, color = labelColor.copy(alpha = alpha))
    }
}

/** Where the selection goes (Figma: Topics 1e). Only topics other than this one are offered. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MoveToTopicSheet(
    targets: List<TopicChoice>,
    count: Int,
    onPick: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Panel,
        shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 18.dp, end = 18.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                if (count == 1) "MOVE 1 ITEM TO" else "MOVE $count ITEMS TO",
                style = MetaStyle.copy(letterSpacing = 1.4.sp),
                color = Muted,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            targets.forEach { target ->
                Text(
                    target.name,
                    style = BodyStyle.copy(fontSize = 15.sp),
                    color = Ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(11.dp))
                        .background(Well)
                        .border(1.dp, Edge, RoundedCornerShape(11.dp))
                        .clickable(role = Role.Button) { onPick(target.id) }
                        .padding(horizontal = 15.dp, vertical = 14.dp),
                )
            }
            Text(
                "A link the other topic already holds isn't moved twice — it is dropped.",
                style = BodyStyle,
                color = Muted,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

private fun modeLabel(mode: TopicViewMode): String = when (mode) {
    TopicViewMode.Feed -> "FEED"
    TopicViewMode.Timeline -> "TIMELINE"
    TopicViewMode.ByType -> "BY TYPE"
}

private fun groupLabel(group: FeedGroup): String = when (group) {
    FeedGroup.Pinned -> "PINNED"
    FeedGroup.Everything -> ""
    is FeedGroup.Period -> when (group.period) {
        TimePeriod.Today -> "TODAY"
        TimePeriod.Yesterday -> "YESTERDAY"
        TimePeriod.ThisWeek -> "THIS WEEK"
        TimePeriod.ThisMonth -> "THIS MONTH"
        TimePeriod.Earlier -> "EARLIER"
    }
    is FeedGroup.Type -> group.type.noun(2).uppercase()
}

/** Stable across recompositions so a heading keeps its place in the lazy list. */
private fun groupKey(group: FeedGroup): String = when (group) {
    FeedGroup.Pinned -> "pinned"
    FeedGroup.Everything -> "all"
    is FeedGroup.Period -> group.period.name
    is FeedGroup.Type -> group.type.name
}

private const val DISABLED_ALPHA = 0.38f

// Selection bars arrive a touch slower than they leave, so leaving feels immediate.
private const val BAR_ENTER_MS = 220
private const val BAR_EXIT_MS = 160

/** View-mode segments: 44dp inside a 48dp track (see [ViewModeSwitch]). */
private val SEGMENT_HEIGHT = 44.dp

@Composable
private fun DetailTopBar(
    pinned: Boolean,
    menuEnabled: Boolean,
    onBack: () -> Unit,
    onSetPinned: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by rememberSaveable { mutableStateOf(false) }
    Box(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 6.dp, vertical = 4.dp),
    ) {
        BarGlyph("‹", "Back", onBack, Modifier.align(Alignment.CenterStart))
        Text(
            "MY INFO / TOPICS",
            style = MetaStyle.copy(letterSpacing = 1.2.sp),
            color = Muted,
            modifier = Modifier.align(Alignment.Center),
        )
        Box(Modifier.align(Alignment.CenterEnd)) {
            BarGlyph("⋯", "Topic options", onClick = { if (menuEnabled) menuOpen = true })
            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
                containerColor = Panel,
            ) {
                DropdownMenuItem(
                    text = { Text(if (pinned) "Unpin" else "Pin to top", color = Ink) },
                    onClick = {
                        menuOpen = false
                        onSetPinned(!pinned)
                    },
                )
                DropdownMenuItem(
                    text = { Text("Delete topic", color = Alarm) },
                    onClick = {
                        menuOpen = false
                        onDelete()
                    },
                )
            }
        }
    }
}

/** A bare glyph as a button. Read by its [label]: TalkBack would otherwise say "‹" or "⋯". */
@Composable
private fun BarGlyph(glyph: String, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Text(
        glyph,
        style = ButtonStyle.copy(fontSize = 20.sp),
        color = Muted,
        textAlign = TextAlign.Center,
        modifier = modifier
            .minimumInteractiveComponentSize()
            .semantics { contentDescription = label }
            .clip(RoundedCornerShape(12.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

@Composable
private fun ActionButton(label: String, primary: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(10.dp)
    Text(
        label,
        style = BodyStyle.copy(fontWeight = if (primary) ButtonStyle.fontWeight else null),
        color = if (primary) Void else Ink,
        textAlign = TextAlign.Center,
        modifier = modifier
            .clip(shape)
            .background(if (primary) Synapse else Panel)
            .then(if (primary) Modifier else Modifier.border(1.dp, Edge, shape))
            .clickable(role = Role.Button, onClick = onClick)
            // 19sp of text and 29dp of padding: a 48dp button.
            .padding(vertical = 14.5.dp),
    )
}

@Composable
private fun FilterChips(state: TopicDetailState, onSelect: (ItemType?) -> Unit) {
    Row(
        modifier = Modifier
            .horizontalScroll(rememberScrollState())
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        FilterChip("ALL ${state.detail?.items?.size ?: 0}", selected = state.activeFilter == null) { onSelect(null) }
        state.filters.forEach { filter ->
            val label = filter.type.noun(2).uppercase()
            FilterChip(
                if (filter.count > 0) "$label ${filter.count}" else label,
                selected = state.activeFilter == filter.type,
            ) { onSelect(filter.type) }
        }
    }
}

@Composable
private fun FilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
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

/** A topic with nothing in it yet (Figma: Topics 1g, "Empty topic"). */
@Composable
private fun EmptyTopic(onAdd: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Panel)
            .dashedBorder(EdgeStrong, cornerRadius = 14.dp)
            .padding(19.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Text("Nothing in here yet", style = ButtonStyle, color = Ink)
        Text(
            "Paste a link, snap a bill, or write the first note.",
            style = BodyStyle,
            color = Muted,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(bottom = 6.dp),
        )
        Text(
            "+ Add first item",
            style = ButtonStyle.copy(fontSize = 14.sp),
            color = Void,
            modifier = Modifier
                .minimumInteractiveComponentSize()
                .clip(RoundedCornerShape(50))
                .background(Synapse)
                .clickable(role = Role.Button, onClick = onAdd)
                .padding(horizontal = 18.dp, vertical = 10.dp),
        )
    }
}

/**
 * Tapping does something: opens a link in the browser or a kept file in whatever handles its
 * type, or copies a note. A bill without an invoice has nothing to do on a tap.
 */
private fun TopicItem.opens(): Boolean =
    type.isLink || storedFile != null || this is TopicItem.Note || this is TopicItem.Email

/** "25 ITEMS · UPDATED 2H AGO". */
private fun metaLine(detail: TopicDetail, nowMillis: Long): String {
    val count = detail.items.size
    return "$count ${if (count == 1) "ITEM" else "ITEMS"} · ${updatedLabel(detail.topic.updatedAtMillis, nowMillis)}"
}

/** @return false when the phone has no browser to open it with. */
private fun Context.openUrl(url: String): Boolean = try {
    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    true
} catch (e: ActivityNotFoundException) {
    false
}


private fun Context.shareText(subject: String, text: String) {
    val send = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_SUBJECT, subject)
        .putExtra(Intent.EXTRA_TEXT, text)
    startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

// ── Previews ──────────────────────────────────────────────────────

@Preview
@Composable
private fun TopicDetailPreview() {
    val now = System.currentTimeMillis()
    val topic = Topic(1, "Trip to Dubai", purpose = null, pinned = true, emptySet(), 0, now - 2 * 3_600_000)
    val items = listOf(
        TopicItem.Video(
            1, 1, now - 2 * 3_600_000,
            SavedLink(1, "https://youtube.com/watch?v=8xQ1n", "Dubai in 3 days — what's actually worth it", thumbnailPath = null),
            durationSeconds = 842, watched = false,
        ),
        TopicItem.Note(2, 1, now - 5 * 3_600_000, "Metro red line closes 00:30 — book a Careem back from the marina, not a street taxi."),
        TopicItem.Note(3, 1, now - 9 * 86_400_000, "Passport has 7 months left — fine for a 30-day visa.", pinned = true),
    )
    KortexTheme {
        TopicDetailScreen(
            state = TopicDetailState(detail = TopicDetail(topic, items), nowMillis = now),
            onIntent = {},
            onBack = {},
        )
    }
}

/** The same feed cut by date, with two items picked out (Figma: Topics 1c, 1e). */
@Preview
@Composable
private fun TopicDetailSelectingPreview() {
    val now = System.currentTimeMillis()
    val topic = Topic(1, "Trip to Dubai", purpose = null, pinned = false, emptySet(), 0, now)
    val items = listOf(
        TopicItem.Note(1, 1, now - 3_600_000, "Metro red line closes 00:30."),
        TopicItem.Note(2, 1, now - 2 * 86_400_000, "Pack adapters — type G sockets."),
        TopicItem.Note(3, 1, now - 40L * 86_400_000, "Hotel booking reference QX-4471."),
    )
    KortexTheme {
        TopicDetailScreen(
            state = TopicDetailState(
                detail = TopicDetail(topic, items),
                mode = TopicViewMode.Timeline,
                selected = setOf(1, 3),
                moveTargets = listOf(TopicChoice(2, "Job switch prep")),
                nowMillis = now,
            ),
            onIntent = {},
            onBack = {},
        )
    }
}
