package dev.kortex.myinfo.topics.ui.search

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kortex.design.Edge
import dev.kortex.design.Ink
import dev.kortex.design.InkSoft
import dev.kortex.design.KortexTheme
import dev.kortex.design.Muted
import dev.kortex.design.Panel
import dev.kortex.design.Synapse
import dev.kortex.design.SynapseDim
import dev.kortex.design.Well
import dev.kortex.mvi.ObserveEffects
import dev.kortex.myinfo.topics.domain.model.Highlighted
import dev.kortex.myinfo.topics.domain.model.ItemType
import dev.kortex.myinfo.topics.domain.model.SearchHit
import dev.kortex.myinfo.topics.domain.model.SearchResults
import dev.kortex.myinfo.topics.domain.model.SearchScope
import dev.kortex.myinfo.topics.domain.model.Topic
import dev.kortex.myinfo.topics.domain.model.TopicItem
import dev.kortex.myinfo.topics.domain.model.TopicResults
import dev.kortex.myinfo.topics.ui.common.BodyStyle
import dev.kortex.myinfo.topics.ui.common.ButtonStyle
import dev.kortex.myinfo.topics.ui.common.ChipStyle
import dev.kortex.myinfo.topics.ui.common.MetaStyle
import dev.kortex.myinfo.topics.ui.common.RowTitleStyle
import dev.kortex.myinfo.topics.ui.common.TypeBadge

/**
 * Full-screen search across every topic (Figma: Topics 1f). [onOpenTopic] leaves for the topic a
 * result sits in; [onClose] backs out.
 */
@Composable
fun TopicSearchRoute(
    onOpenTopic: (Long) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: TopicSearchViewModel = hiltViewModel(),
) {
    BackHandler(onBack = onClose)
    val state by viewModel.state.collectAsStateWithLifecycle()
    ObserveEffects(viewModel.effects) { effect ->
        when (effect) {
            is TopicSearchEffect.OpenTopic -> onOpenTopic(effect.topicId)
        }
    }
    TopicSearchScreen(state = state, onIntent = viewModel::onIntent, onClose = onClose, modifier = modifier)
}

@Composable
fun TopicSearchScreen(
    state: TopicSearchState,
    onIntent: (TopicSearchIntent) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by rememberSaveable { mutableStateOf(state.query) }
    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        Column(Modifier.padding(innerPadding)) {
            SearchField(
                query = query,
                onQueryChange = {
                    query = it
                    onIntent(TopicSearchIntent.QueryChanged(it))
                },
                onClear = {
                    query = ""
                    onIntent(TopicSearchIntent.QueryChanged(""))
                },
                onClose = onClose,
            )
            ScopeChips(state, onSelect = { onIntent(TopicSearchIntent.SelectScope(it)) })
            ResultSummary(state)
            SearchResultsList(state, onOpenTopic = { onIntent(TopicSearchIntent.OpenTopic(it)) })
        }
    }
}

@Composable
private fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onClear: () -> Unit,
    onClose: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            "‹",
            style = ButtonStyle.copy(fontSize = 20.sp),
            color = Muted,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .minimumInteractiveComponentSize()
                .semantics { contentDescription = "Back" }
                .clip(RoundedCornerShape(12.dp))
                .clickable(role = Role.Button, onClick = onClose)
                .padding(horizontal = 10.dp, vertical = 8.dp),
        )
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            singleLine = true,
            textStyle = RowTitleStyle.copy(color = Ink),
            cursorBrush = SolidColor(Synapse),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, imeAction = ImeAction.Search),
            interactionSource = interactionSource,
            modifier = Modifier
                .weight(1f)
                .focusRequester(focusRequester),
            decorationBox = { innerTextField ->
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(FieldShape)
                        .background(Well)
                        .border(1.dp, if (focused) Synapse else Edge, FieldShape)
                        .padding(start = 14.dp, end = if (query.isEmpty()) 14.dp else 52.dp, top = 12.dp, bottom = 12.dp),
                ) {
                    if (query.isEmpty()) Text("Search your topics", style = RowTitleStyle, color = Muted, maxLines = 1)
                    innerTextField()
                }
            },
        )
        if (query.isNotEmpty()) {
            Text(
                "✕",
                style = ButtonStyle,
                color = Muted,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .semantics { contentDescription = "Clear search" }
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(role = Role.Button, onClick = onClear)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun ScopeChips(state: TopicSearchState, onSelect: (SearchScope) -> Unit) {
    Row(
        modifier = Modifier
            .horizontalScroll(rememberScrollState())
            .padding(start = 18.dp, end = 18.dp)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        state.scopes.forEach { scope ->
            val isSelected = scope == state.scope
            Text(
                scopeLabel(scope),
                style = ChipStyle,
                color = if (isSelected) Synapse else Muted,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .clip(ChipShape)
                    .background(if (isSelected) SynapseDim else Panel)
                    .border(1.dp, if (isSelected) Synapse else Edge, ChipShape)
                    .selectable(selected = isSelected, role = Role.Tab) { onSelect(scope) }
                    .padding(horizontal = 12.dp, vertical = 7.dp),
            )
        }
    }
}

/** "12 ITEMS IN 3 TOPICS", or what to do before anything has been typed. */
@Composable
private fun ResultSummary(state: TopicSearchState) {
    val line = when {
        state.idle -> "NOTES, LINKS, FILES AND BILLS — ACROSS EVERY TOPIC"
        state.searching -> "SEARCHING…"
        state.results.empty -> "NOTHING MATCHED"
        else -> summaryLine(state.results)
    }
    Text(
        line,
        style = MetaStyle.copy(letterSpacing = 1.2.sp),
        color = Muted,
        modifier = Modifier
            .padding(start = 18.dp, end = 18.dp, top = 6.dp, bottom = 10.dp)
            // Read out once typing settles, so a screen-reader user hears how the search went.
            .semantics { liveRegion = LiveRegionMode.Polite },
    )
}

@Composable
private fun SearchResultsList(state: TopicSearchState, onOpenTopic: (Long) -> Unit) {
    if (state.idle) {
        SearchHint()
        return
    }
    if (state.noResults) {
        Text(
            "Nothing in your topics matches “${state.query}”.",
            style = BodyStyle,
            color = Muted,
            modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 6.dp),
        )
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Rows slide to their new places as the query narrows, rather than the list jumping.
        state.results.groups.forEach { group ->
            item(key = "topic-${group.topic.id}") {
                TopicResultHeader(group, onClick = { onOpenTopic(group.topic.id) }, modifier = Modifier.animateItem())
            }
            items(group.hits, key = { it.item.id }) { hit ->
                HitRow(hit, onClick = { onOpenTopic(group.topic.id) }, modifier = Modifier.animateItem())
            }
        }
    }
}

/** The topic a run of results sits in; tapping it opens the topic itself. A heading, for TalkBack to jump between. */
@Composable
private fun TopicResultHeader(group: TopicResults, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .heightIn(min = 48.dp)
            .semantics { heading() }
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClickLabel = "Open topic", role = Role.Button, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Text(
            highlightedText(group.name),
            style = ButtonStyle,
            color = Ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Text(
            if (group.hits.isEmpty()) "NAME" else "${group.hits.size}",
            style = MetaStyle,
            color = if (group.hits.isEmpty()) Synapse else Muted,
        )
    }
}

/** One matching item: its type, the line it matched on, and the address or code under it. */
@Composable
private fun HitRow(hit: SearchHit, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RowShape)
            .background(Panel)
            .border(1.dp, Edge, RowShape)
            .clickable(onClickLabel = "Open the topic this is in", role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        TypeBadge(hit.item.type, size = 30.dp, cornerRadius = 7.dp)
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                highlightedText(hit.title).ifEmpty { AnnotatedString(untitled(hit.item.type)) },
                style = RowTitleStyle,
                color = InkSoft,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            hit.detail?.takeIf { it.text.isNotBlank() }?.let { detail ->
                Text(
                    highlightedText(detail),
                    style = MetaStyle.copy(letterSpacing = 0.sp),
                    color = Muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** What the screen says before anything is typed. */
@Composable
private fun SearchHint() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 18.dp, end = 18.dp, top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            "Type a word or two. Every word has to appear — “dubai visa” finds what mentions both.",
            style = BodyStyle,
            color = Muted,
        )
        Text(
            "Topic names count too, so you can jump straight to one.",
            style = BodyStyle,
            color = Muted,
        )
    }
}

/**
 * The matched stretches picked out in the accent on its own tint; everything else keeps the
 * colour the surrounding [Text] gives it.
 */
private fun highlightedText(highlighted: Highlighted): AnnotatedString {
    val text = highlighted.text
    if (highlighted.matches.isEmpty()) return AnnotatedString(text)
    return buildAnnotatedString {
        var at = 0
        highlighted.matches.forEach { range ->
            val start = range.first.coerceIn(0, text.length)
            val end = (range.last + 1).coerceIn(start, text.length)
            if (start > at) append(text.substring(at, start))
            withStyle(SpanStyle(color = Synapse, background = SynapseDim, fontWeight = FontWeight.Medium)) {
                append(text.substring(start, end))
            }
            at = end
        }
        if (at < text.length) append(text.substring(at))
    }
}

private fun AnnotatedString.ifEmpty(fallback: AnnotatedString): AnnotatedString = if (text.isEmpty()) fallback else this

/** An image with no caption still turns up under a matching topic; it needs something to read. */
private fun untitled(type: ItemType): String = when (type) {
    ItemType.Image -> "Untitled image"
    ItemType.Note -> "Empty note"
    else -> "Untitled"
}

private fun scopeLabel(scope: SearchScope): String = when (scope) {
    SearchScope.Everything -> "EVERYTHING"
    SearchScope.Notes -> "NOTES"
    SearchScope.Links -> "LINKS"
    SearchScope.Files -> "FILES"
    SearchScope.Bills -> "BILLS"
    SearchScope.Emails -> "EMAILS"
}

/** "12 ITEMS IN 3 TOPICS", or "3 TOPICS" when only names matched. */
private fun summaryLine(results: SearchResults): String {
    val topics = "${results.topicCount} ${if (results.topicCount == 1) "TOPIC" else "TOPICS"}"
    if (results.itemCount == 0) return topics
    return "${results.itemCount} ${if (results.itemCount == 1) "ITEM" else "ITEMS"} IN $topics"
}

private val FieldShape = RoundedCornerShape(11.dp)
private val ChipShape = RoundedCornerShape(8.dp)
private val RowShape = RoundedCornerShape(12.dp)

// ── Previews ──────────────────────────────────────────────────────

@Preview
@Composable
private fun TopicSearchPreview() {
    val topic = Topic(1, "Trip to Dubai", purpose = null, pinned = false, emptySet(), 0, 0)
    val note = TopicItem.Note(1, 1, 0, "Metro red line closes 00:30 in Dubai")
    val results = SearchResults(
        query = "dubai",
        scope = SearchScope.Everything,
        groups = listOf(
            TopicResults(
                topic = topic,
                name = Highlighted("Trip to Dubai", listOf(8..12)),
                hits = listOf(SearchHit(note, Highlighted(note.text, listOf(30..34)), detail = null)),
            ),
        ),
    )
    KortexTheme {
        TopicSearchScreen(
            state = TopicSearchState(query = "dubai", results = results, idle = false),
            onIntent = {},
            onClose = {},
        )
    }
}

@Preview
@Composable
private fun TopicSearchIdlePreview() {
    KortexTheme {
        TopicSearchScreen(state = TopicSearchState(), onIntent = {}, onClose = {})
    }
}
