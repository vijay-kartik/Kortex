package dev.kortex.links.ui

import android.view.HapticFeedbackConstants
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kortex.design.R
import dev.kortex.design.Ink
import dev.kortex.design.KortexTheme
import dev.kortex.design.Muted
import dev.kortex.design.Panel
import dev.kortex.design.Synapse
import dev.kortex.design.SynapseDim
import dev.kortex.design.Void
import dev.kortex.links.ui.components.TagChip
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
    // One card animates at a time: a tap anywhere restarts the animation on the tapped card.
    var copyTap by remember { mutableStateOf<CopyTap?>(null) }
    var searchExpanded by rememberSaveable { mutableStateOf(false) }

    Column(modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 16.dp, end = 16.dp, top = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            LinksSearchHeader(
                linkCount = state.links.size,
                tagCount = state.tags.size,
                query = query,
                onQueryChange = onQueryChange,
                expanded = searchExpanded,
                onExpandedChange = { searchExpanded = it },
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
                    val copyTick = copyTap?.takeIf { it.linkId == link.link.id }?.tick
                    LinkCard(
                        link = link,
                        nowMillis = nowMillis,
                        copyTick = copyTick,
                        onCopy = {
                            copyTap = CopyTap(link.link.id, tick = (copyTap?.tick ?: 0) + 1)
                            onLinkClick(link.link)
                        },
                        // Clear once finished so a card scrolled back into view doesn't replay it.
                        onCopyFinished = { if (copyTap?.tick == copyTick) copyTap = null },
                    )
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

private data class CopyTap(val linkId: Long, val tick: Int)

@Composable
private fun LinkCard(
    link: LinkWithTags,
    nowMillis: Long,
    copyTick: Int?,
    onCopy: () -> Unit,
    onCopyFinished: () -> Unit,
) {
    val copyAnimation = rememberLinkCopyAnimation(copyTick, onCopyFinished)
    val view = LocalView.current
    val currentOnCopy by rememberUpdatedState(onCopy)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(LinkCardShape)
            // Raw tap detection instead of clickable: the haptic lands on touch-down, not release,
            // and there's no ripple because the card's fill must never change.
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = { view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK) },
                    onTap = { currentOnCopy() },
                )
            }
            .semantics {
                onClick(label = "Copy link") {
                    currentOnCopy()
                    true
                }
            }
            .background(Panel)
            .drawBehind {
                val thumbnailCenter = (LinkCardInset + LinkThumbnailSize / 2).toPx()
                with(copyAnimation) { drawEffects(Offset(thumbnailCenter, thumbnailCenter)) }
            }
            .border(1.dp, copyAnimation.borderColor, LinkCardShape)
            .padding(LinkCardInset),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        LinkCardThumbnail(link = link.link, copyAnimation = copyAnimation)
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                link.link.title.ifBlank { linkDomain(link.link.url) ?: link.link.url },
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

private val COPY_BADGE_SIZE = 28.dp
private const val IMAGE_ARRIVAL_MS = 200

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
