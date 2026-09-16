package dev.kortex.app.ui.screens.links

import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kortex.app.R
import dev.kortex.app.ui.Edge
import dev.kortex.app.ui.Grotesk
import dev.kortex.app.ui.Ink
import dev.kortex.app.ui.KortexTheme
import dev.kortex.app.ui.Mono
import dev.kortex.app.ui.Muted
import dev.kortex.app.ui.Panel
import dev.kortex.app.ui.Synapse
import dev.kortex.app.ui.SynapseDim
import dev.kortex.app.ui.Void
import dev.kortex.app.ui.components.TagChip
import dev.kortex.links.data.LinkEntity
import dev.kortex.links.data.LinkWithTags
import dev.kortex.links.data.TagLinkCount


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
            modifier = modifier.padding(innerPadding).fillMaxSize().imePadding(),
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
                    onLinkClick = { link ->
                        clipboard.setText(AnnotatedString(link.url))
                        // Android 13+ shows its own clipboard confirmation.
                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                            Toast.makeText(context, "Link copied", Toast.LENGTH_SHORT).show()
                        }
                    },
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

@Composable
fun LinksWithSearchScreen(
    state: LinksScreenUiState.LinksUiState,
    query: String,
    onQueryChange: (String) -> Unit,
    onTagToggle: (String) -> Unit,
    onLinkClick: (LinkEntity) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Ages only need minute precision; refresh them whenever the list itself changes.
    val nowMillis = remember(state.links) { System.currentTimeMillis() }

    Column(modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 16.dp, end = 16.dp, top = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            LinksHeader(
                linkCount = state.links.size,
                tagCount = state.tags.size,
                query = query,
                onQueryChange = onQueryChange,
            )
            if (state.tags.isNotEmpty()) {
                TagFilters(tags = state.tags, selectedTags = state.selectedTags, onTagToggle = onTagToggle)
            }
            LazyColumn(
                modifier = Modifier.weight(1f),
                // Extra bottom room so the FAB never covers the last card.
                contentPadding = PaddingValues(bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(state.links, key = { it.link.id }) { link ->
                    LinkCard(link = link, nowMillis = nowMillis, onClick = { onLinkClick(link.link) })
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
    }
}

@Composable
private fun LinksHeader(linkCount: Int, tagCount: Int, query: String, onQueryChange: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            "${countLabel(linkCount, "LINK")} · ${countLabel(tagCount, "TAG")}",
            style = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 1.sp),
            color = Muted,
        )
        Box(Modifier.weight(1f))
        SearchField(query = query, onQueryChange = onQueryChange)
    }
}

/** Hugs "Search" at rest and grows with the query, up to a cap. */
@Composable
private fun SearchField(query: String, onQueryChange: (String) -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    val textStyle = MaterialTheme.typography.labelLarge
    BasicTextField(
        value = query,
        onValueChange = onQueryChange,
        singleLine = true,
        textStyle = textStyle.copy(color = Ink),
        cursorBrush = SolidColor(Synapse),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        modifier = Modifier.widthIn(max = 220.dp),
        decorationBox = { innerTextField ->
            Row(
                modifier = Modifier
                    .clip(shape)
                    .background(Panel)
                    .border(1.dp, Edge, shape)
                    .padding(start = 12.dp, end = 16.dp, top = 9.dp, bottom = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    painterResource(R.drawable.ic_search),
                    contentDescription = null,
                    tint = Muted,
                    modifier = Modifier.size(16.dp),
                )
                Box {
                    if (query.isEmpty()) Text("Search", style = textStyle, color = Muted)
                    innerTextField()
                }
            }
        },
    )
}

@Composable
private fun TagFilters(tags: List<TagLinkCount>, selectedTags: Set<String>, onTagToggle: (String) -> Unit) {
    Row(
        modifier = Modifier
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

@Composable
private fun LinkCard(link: LinkWithTags, nowMillis: Long, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .clickable(onClick = onClick)
            .background(Panel)
            .border(1.dp, Edge, shape)
            .padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier
                .size(36.dp)
                .background(SynapseDim, RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painterResource(R.drawable.ic_link),
                contentDescription = null,
                tint = Synapse,
                modifier = Modifier.size(20.dp),
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                link.link.title.ifBlank { linkDomain(link.link.url) ?: link.link.url },
                style = TextStyle(fontFamily = Grotesk, fontWeight = FontWeight.Medium, fontSize = 17.sp, lineHeight = 24.sp, letterSpacing = 0.15.sp),
                color = Ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                link.link.url,
                style = TextStyle(fontFamily = Mono, fontSize = 12.sp, lineHeight = 16.sp),
                color = Muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                buildAnnotatedString {
                    if (link.tagNames.isNotEmpty()) {
                        withStyle(SpanStyle(color = Synapse)) { append(link.tagNames.joinToString(" · ").uppercase()) }
                        append(" · ")
                    }
                    append(relativeAge(link.link.createdAtMillis, nowMillis))
                },
                style = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 16.sp, letterSpacing = 1.2.sp),
                color = Muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun countLabel(count: Int, noun: String) = "$count ${if (count == 1) noun else noun + "S"}"

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
                    tags = listOf(TagLinkCount("sample", 1), TagLinkCount("ticket", 0), TagLinkCount("to buy", 1)),
                    selectedTags = emptySet(),
                ),
                query = "",
                onQueryChange = {},
                onTagToggle = {},
                onLinkClick = {},
            )
        }
    }
}
