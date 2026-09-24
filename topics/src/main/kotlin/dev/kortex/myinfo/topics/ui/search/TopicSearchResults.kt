package dev.kortex.myinfo.topics.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kortex.design.Edge
import dev.kortex.design.Ink
import dev.kortex.design.InkSoft
import dev.kortex.design.KortexTheme
import dev.kortex.design.Muted
import dev.kortex.design.Panel
import dev.kortex.design.Synapse
import dev.kortex.design.SynapseDim
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
 * Search across every topic (Figma: Topics 1f), shown in place of the topics list while the list's
 * search field holds a query: scope chips over the results, grouped by the topic they sit in.
 * The field and the "12 ITEMS IN 3 TOPICS" line belong to the list's header; see [searchSummary].
 */
@Composable
internal fun TopicSearchResults(
    state: TopicSearchState,
    onIntent: (TopicSearchIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        ScopeChips(state, onSelect = { onIntent(TopicSearchIntent.SelectScope(it)) })
        SearchResultsList(state, onOpenTopic = { onIntent(TopicSearchIntent.OpenTopic(it)) })
    }
}

/** "12 ITEMS IN 3 TOPICS", or how the search is going; read out once typing settles. */
internal fun searchSummary(state: TopicSearchState): String = when {
    state.searching -> "SEARCHING…"
    state.results.empty -> "NOTHING MATCHED"
    else -> summaryLine(state.results)
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

@Composable
private fun SearchResultsList(state: TopicSearchState, onOpenTopic: (Long) -> Unit) {
    if (state.noResults) {
        Text(
            "Nothing in your topics matches “${state.query}”.",
            style = BodyStyle,
            color = Muted,
            modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 12.dp),
        )
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        // Extra bottom room so the FAB never covers the last row.
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 4.dp, bottom = 96.dp),
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

private val ChipShape = RoundedCornerShape(8.dp)
private val RowShape = RoundedCornerShape(12.dp)

// ── Previews ──────────────────────────────────────────────────────

@Preview
@Composable
private fun TopicSearchResultsPreview() {
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
        TopicSearchResults(
            state = TopicSearchState(query = "dubai", results = results, idle = false),
            onIntent = {},
        )
    }
}
