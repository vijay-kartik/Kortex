package dev.kortex.myinfo.topics.ui.list

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kortex.design.Edge
import dev.kortex.design.KortexTheme
import dev.kortex.design.Muted
import dev.kortex.design.Panel
import dev.kortex.design.Synapse
import dev.kortex.design.SynapseDim
import dev.kortex.design.Void
import dev.kortex.design.anim.StandardEasing
import dev.kortex.mvi.ObserveEffects
import dev.kortex.myinfo.topics.domain.model.ItemType
import dev.kortex.myinfo.topics.domain.model.Money
import dev.kortex.myinfo.topics.domain.model.Progress
import dev.kortex.myinfo.topics.domain.model.Topic
import dev.kortex.myinfo.topics.domain.model.TopicOverview
import dev.kortex.myinfo.topics.domain.model.TopicSort
import dev.kortex.myinfo.topics.ui.common.BodyStyle
import dev.kortex.myinfo.topics.ui.common.ChipStyle
import dev.kortex.myinfo.topics.ui.common.MetaStyle

/** Connects [TopicsListScreen] to its ViewModel and hands navigation to the host. */
@Composable
fun TopicsListRoute(
    onOpenTopic: (Long) -> Unit,
    onCreateTopic: () -> Unit,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: TopicsListViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ObserveEffects(viewModel.effects) { effect ->
        when (effect) {
            is TopicsListEffect.OpenTopic -> onOpenTopic(effect.topicId)
            TopicsListEffect.OpenNewTopic -> onCreateTopic()
            TopicsListEffect.OpenSearch -> onSearch()
        }
    }
    TopicsListScreen(state = state, onIntent = viewModel::onIntent, modifier = modifier)
}

@Composable
fun TopicsListScreen(
    state: TopicsListState,
    onIntent: (TopicsListIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    when {
        state.loading -> Unit
        state.firstRun -> TopicsFirstRun(
            suggestions = state.suggestions,
            onAccept = { onIntent(TopicsListIntent.AcceptSuggestion(it)) },
            onCreate = { onIntent(TopicsListIntent.CreateTopic) },
            modifier = modifier,
        )
        else -> Scaffold(
            modifier = modifier,
            containerColor = MaterialTheme.colorScheme.background,
            // The home Scaffold already pads for the system bars.
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            floatingActionButton = {
                FloatingActionButton(
                    onClick = { onIntent(TopicsListIntent.CreateTopic) },
                    shape = RoundedCornerShape(14.dp),
                    containerColor = Synapse,
                    contentColor = Void,
                ) {
                    Icon(Icons.Default.Add, contentDescription = "New topic")
                }
            },
        ) { innerPadding ->
            TopicsList(state, onIntent, Modifier.padding(innerPadding))
        }
    }
}

@Composable
private fun TopicsList(
    state: TopicsListState,
    onIntent: (TopicsListIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Ages only need minute precision; refresh them whenever the list itself changes.
    val nowMillis = remember(state.topics) { System.currentTimeMillis() }
    val openId = state.openOptionsTopicId
    val optionsOpen = openId != null
    val currentOptionsOpen by rememberUpdatedState(optionsOpen)
    val currentOnIntent by rememberUpdatedState(onIntent)
    val tapOutside = remember { OpenCardBounds() }

    BackHandler(enabled = optionsOpen) { onIntent(TopicsListIntent.HideOptions) }

    // The pressed card dims everything else, as on the Links tab.
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
                    currentOnIntent(TopicsListIntent.HideOptions)
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        event.changes.forEach { it.consume() }
                    } while (event.changes.any { it.pressed })
                }
            },
    ) {
        Column(Modifier.graphicsLayer { alpha = chromeAlpha }) {
            Text(
                "${state.topicCount} ${if (state.topicCount == 1) "TOPIC" else "TOPICS"} · " +
                    "${state.itemCount} ${if (state.itemCount == 1) "ITEM" else "ITEMS"}",
                style = MetaStyle.copy(letterSpacing = 1.2.sp),
                color = Muted,
                modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 12.dp),
            )
            // The sort chips stand in 48dp slots, 10dp taller than they look above and below, so
            // the padding around them is 10dp less than the gaps it leaves.
            SearchPill(
                onClick = { onIntent(TopicsListIntent.Search) },
                modifier = Modifier.padding(start = 18.dp, end = 18.dp, bottom = 2.dp),
            )
            SortChips(
                selected = state.sort,
                pinnedCount = state.pinnedCount,
                onSelect = { onIntent(TopicsListIntent.SelectSort(it)) },
                modifier = Modifier.padding(start = 18.dp, end = 18.dp, bottom = 4.dp),
            )
        }
        LazyColumn(
            modifier = Modifier.weight(1f),
            // Extra bottom room so the FAB never covers the last card.
            contentPadding = PaddingValues(start = 18.dp, end = 18.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(state.visibleTopics, key = { it.topic.id }) { overview ->
                val topicId = overview.topic.id
                val isOpen = topicId == openId
                val rowAlpha by animateFloatAsState(
                    targetValue = if (optionsOpen && !isOpen) DIMMED_ALPHA else 1f,
                    animationSpec = tween(PRESS_MS, easing = StandardEasing),
                    label = "row dim",
                )
                AnimatedContent(
                    targetState = state.pendingDeletion?.takeIf { it.topicId == topicId },
                    transitionSpec = { fadeIn(tween(SWAP_MS)) togetherWith fadeOut(tween(SWAP_MS)) },
                    contentKey = { it != null },
                    modifier = Modifier
                        .animateItem()
                        .graphicsLayer { alpha = rowAlpha },
                    label = "topic row",
                ) { deletion ->
                    if (deletion != null) {
                        DeletedTopicRow(
                            name = overview.topic.name,
                            deletion = deletion,
                            onUndo = { onIntent(TopicsListIntent.UndoDelete) },
                        )
                    } else {
                        TopicCard(
                            overview = overview,
                            nowMillis = nowMillis,
                            optionsOpen = isOpen,
                            onOpen = { onIntent(TopicsListIntent.OpenTopic(topicId)) },
                            onLongPress = { if (!isOpen) onIntent(TopicsListIntent.ShowOptions(topicId)) },
                            onTogglePin = { onIntent(TopicsListIntent.SetPinned(topicId, !overview.topic.pinned)) },
                            onDelete = { onIntent(TopicsListIntent.Delete(topicId)) },
                            modifier = if (isOpen) Modifier.onGloballyPositioned { tapOutside.card = it } else Modifier,
                        )
                    }
                }
            }
            if (state.visibleTopics.isEmpty()) {
                item {
                    Text(
                        "No pinned topics. Long-press a topic to pin it.",
                        style = BodyStyle,
                        color = Muted,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }
        Text(
            if (optionsOpen) "tap outside or back to close" else "long-press a topic for options",
            style = MetaStyle.copy(letterSpacing = 0.6.sp),
            color = Muted,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 18.dp, top = 8.dp, bottom = 30.dp),
        )
    }
}

/** Opens search across every topic (Figma: Topics 1a, 1f). A pill, not a field: the screen it
 * opens owns the query and the keyboard. */
@Composable
private fun SearchPill(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Panel)
            .border(1.dp, Edge, shape)
            .clickable(onClickLabel = "Search topics", role = Role.Button, onClick = onClick)
            .heightIn(min = 48.dp)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Decoration: the words say what the pill does, so TalkBack skips the glyph.
        Text("⌕", style = BodyStyle.copy(fontSize = 17.sp), color = Muted, modifier = Modifier.clearAndSetSemantics { })
        Text("Search notes, links, files and bills", style = BodyStyle, color = Muted)
    }
}

@Composable
private fun SortChips(
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

/** Where the open card sits within the screen, so a tap anywhere else can close its tray. */
private class OpenCardBounds {
    var screen: LayoutCoordinates? = null
    var card: LayoutCoordinates? = null

    /** [position] is in [screen] coordinates. Only the card's visible part counts. */
    fun contains(position: Offset): Boolean {
        val screen = screen?.takeIf { it.isAttached } ?: return false
        val card = card?.takeIf { it.isAttached } ?: return false
        return screen.localBoundingBoxOf(card).contains(position)
    }
}

private const val DIMMED_ALPHA = 0.35f
private const val SWAP_MS = 200

// ── Previews ──────────────────────────────────────────────────────

@Preview
@Composable
private fun TopicsListPreview() {
    val now = System.currentTimeMillis()
    fun topic(id: Long, name: String, pinned: Boolean = false, hoursAgo: Long) =
        Topic(id, name, purpose = null, pinned, emptySet(), createdAtMillis = 0, updatedAtMillis = now - hoursAgo * 3_600_000)
    val topics = listOf(
        TopicOverview(
            topic(1, "Trip to Dubai", pinned = true, hoursAgo = 2),
            itemCount = 25,
            counts = mapOf(ItemType.Link to 9, ItemType.Video to 5, ItemType.Bill to 3, ItemType.Doc to 6, ItemType.Note to 2),
            previews = List(8) { "missing-$it.jpg" },
            billTotals = listOf(Money(428_000, "AED")),
            reading = null,
        ),
        TopicOverview(
            topic(2, "Papa — medical records", hoursAgo = 30),
            itemCount = 18,
            counts = mapOf(ItemType.Doc to 12, ItemType.Image to 4, ItemType.Bill to 2),
            previews = emptyList(),
            billTotals = emptyList(),
            reading = null,
        ),
        TopicOverview(
            topic(3, "Job switch prep", hoursAgo = 72),
            itemCount = 15,
            counts = mapOf(ItemType.Article to 8, ItemType.Note to 5, ItemType.Link to 2),
            previews = emptyList(),
            billTotals = emptyList(),
            reading = Progress(done = 5, total = 8),
        ),
    )
    KortexTheme {
        TopicsListScreen(state = TopicsListState(loading = false, topics = topics), onIntent = {})
    }
}
