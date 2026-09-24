package dev.kortex.myinfo.topics.ui.list

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kortex.design.KortexTheme
import dev.kortex.design.SearchHeader
import dev.kortex.design.countLabel
import dev.kortex.design.Muted
import dev.kortex.design.Synapse
import dev.kortex.design.Void
import dev.kortex.design.anim.StandardEasing
import dev.kortex.mvi.ObserveEffects
import dev.kortex.myinfo.topics.domain.model.ItemType
import dev.kortex.myinfo.topics.domain.model.Money
import dev.kortex.myinfo.topics.domain.model.Progress
import dev.kortex.myinfo.topics.domain.model.Topic
import dev.kortex.myinfo.topics.domain.model.TopicOverview
import dev.kortex.myinfo.topics.ui.common.BodyStyle
import dev.kortex.myinfo.topics.ui.list.components.DeletedTopicRow
import dev.kortex.myinfo.topics.ui.list.components.SortChips
import dev.kortex.myinfo.topics.ui.list.components.TopicCard
import dev.kortex.myinfo.topics.ui.search.TopicSearchEffect
import dev.kortex.myinfo.topics.ui.search.TopicSearchIntent
import dev.kortex.myinfo.topics.ui.search.TopicSearchResults
import dev.kortex.myinfo.topics.ui.search.TopicSearchState
import dev.kortex.myinfo.topics.ui.search.TopicSearchViewModel
import dev.kortex.myinfo.topics.ui.search.searchSummary

/** Connects [TopicsListScreen] to its ViewModel and hands navigation to the host. */
@Composable
fun TopicsListRoute(
    onOpenTopic: (Long) -> Unit,
    onCreateTopic: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: TopicsListViewModel = hiltViewModel(),
    searchViewModel: TopicSearchViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val search by searchViewModel.state.collectAsStateWithLifecycle()
    ObserveEffects(viewModel.effects) { effect ->
        when (effect) {
            is TopicsListEffect.OpenTopic -> onOpenTopic(effect.topicId)
            TopicsListEffect.OpenNewTopic -> onCreateTopic()
        }
    }
    ObserveEffects(searchViewModel.effects) { effect ->
        when (effect) {
            is TopicSearchEffect.OpenTopic -> onOpenTopic(effect.topicId)
        }
    }
    TopicsListScreen(
        state = state,
        search = search,
        onIntent = viewModel::onIntent,
        onSearchIntent = searchViewModel::onIntent,
        modifier = modifier,
    )
}

@Composable
fun TopicsListScreen(
    state: TopicsListState,
    search: TopicSearchState,
    onIntent: (TopicsListIntent) -> Unit,
    onSearchIntent: (TopicSearchIntent) -> Unit,
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
            TopicsList(
                state = state,
                search = search,
                onIntent = onIntent,
                onSearchIntent = onSearchIntent,
                modifier = Modifier
                    .padding(innerPadding)
                    // The nav bar is already padded by the parent, so the keyboard only adds what's above it.
                    .consumeWindowInsets(WindowInsets.navigationBars)
                    .imePadding(),
            )
        }
    }
}

/**
 * The search field works as on the Links tab: it expands in place, and while it holds a query the
 * scope chips and results across every topic stand in for the sort chips and topic cards.
 */
@Composable
private fun TopicsList(
    state: TopicsListState,
    search: TopicSearchState,
    onIntent: (TopicsListIntent) -> Unit,
    onSearchIntent: (TopicSearchIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Ages only need minute precision; refresh them whenever the list itself changes.
    val nowMillis = remember(state.topics) { System.currentTimeMillis() }
    val optionsOpen = state.openOptionsTopicId != null
    val tapOutside = remember { OpenCardBounds() }

    // The field reads local state so typing never waits on the debounced search. It starts from the
    // search's query, which outlives this screen while a topic opened from the results is shown.
    var query by rememberSaveable { mutableStateOf(search.query) }
    var searchExpanded by rememberSaveable { mutableStateOf(false) }
    val showResults = query.isNotBlank()
    // A restored query whose search didn't survive: run it again.
    LaunchedEffect(Unit) {
        if (showResults && search.query != query) onSearchIntent(TopicSearchIntent.QueryChanged(query))
    }

    // The pressed card dims everything else, as on the Links tab.
    val chromeAlpha by animateFloatAsState(
        targetValue = if (optionsOpen) DIMMED_ALPHA else 1f,
        animationSpec = tween(TRAY_MS, delayMillis = if (optionsOpen) PRESS_MS else 0, easing = StandardEasing),
        label = "chrome dim",
    )

    Column(
        modifier
            .fillMaxSize()
            .tapOutsideToClose(tapOutside, open = optionsOpen) { onIntent(TopicsListIntent.HideOptions) },
    ) {
        SearchHeader(
            meta = if (showResults) {
                searchSummary(search)
            } else {
                "${countLabel(state.topicCount, "TOPIC")} · ${countLabel(state.itemCount, "ITEM")}"
            },
            query = query,
            onQueryChange = {
                query = it
                onSearchIntent(TopicSearchIntent.QueryChanged(it))
            },
            expanded = searchExpanded,
            onExpandedChange = { searchExpanded = it },
            modifier = Modifier
                .padding(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 6.dp)
                .graphicsLayer { alpha = chromeAlpha },
        )
        // Registered after the header's, so back closes the tray before it collapses search.
        BackHandler(enabled = optionsOpen) { onIntent(TopicsListIntent.HideOptions) }
        Crossfade(targetState = showResults, modifier = Modifier.weight(1f), label = "list or results") { results ->
            if (results) {
                TopicSearchResults(state = search, onIntent = onSearchIntent, modifier = Modifier.fillMaxSize())
            } else {
                TopicCards(state, nowMillis, chromeAlpha, tapOutside, onIntent)
            }
        }
    }
}

@Composable
private fun TopicCards(
    state: TopicsListState,
    nowMillis: Long,
    chromeAlpha: Float,
    tapOutside: OpenCardBounds,
    onIntent: (TopicsListIntent) -> Unit,
) {
    val openId = state.openOptionsTopicId
    val optionsOpen = openId != null
    Column(Modifier.fillMaxSize()) {
        // The sort chips stand in 48dp slots, 10dp taller than they look above and below.
        SortChips(
            selected = state.sort,
            pinnedCount = state.pinnedCount,
            onSelect = { onIntent(TopicsListIntent.SelectSort(it)) },
            modifier = Modifier
                .padding(start = 18.dp, end = 18.dp, bottom = 4.dp)
                .graphicsLayer { alpha = chromeAlpha },
        )
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
    }
}

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
        TopicsListScreen(
            state = TopicsListState(loading = false, topics = topics),
            search = TopicSearchState(),
            onIntent = {},
            onSearchIntent = {},
        )
    }
}
