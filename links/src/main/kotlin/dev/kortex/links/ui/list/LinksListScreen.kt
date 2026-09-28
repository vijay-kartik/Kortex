package dev.kortex.links.ui.list

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kortex.design.KortexTheme
import dev.kortex.design.MetaLine
import dev.kortex.design.Muted
import dev.kortex.design.R
import dev.kortex.design.Synapse
import dev.kortex.design.SynapseDim
import dev.kortex.design.TopBarSearch
import dev.kortex.design.Void
import dev.kortex.design.anim.EmphasizedAccelerate
import dev.kortex.design.anim.EmphasizedDecelerate
import dev.kortex.design.anim.StandardEasing
import dev.kortex.design.countLabel
import dev.kortex.design.resultsLabel
import dev.kortex.links.domain.model.Link
import dev.kortex.links.domain.model.TagCount
import dev.kortex.links.ui.list.components.DIMMED_ALPHA
import dev.kortex.links.ui.list.components.DeletedLinkRow
import dev.kortex.links.ui.list.components.LinkCard
import dev.kortex.links.ui.list.components.LinkTagEditor
import dev.kortex.links.ui.list.components.PRESS_MS
import dev.kortex.links.ui.list.components.SWAP_MS
import dev.kortex.links.ui.list.components.TRAY_MS
import dev.kortex.links.ui.list.components.TagFilters
import dev.kortex.mvi.ObserveEffects

/**
 * Connects [LinksListScreen] to its ViewModel. The search field lives in the home top bar:
 * [search] brings its query here, and learns whether there is anything to search.
 */
@Composable
fun LinksRoute(
    search: TopBarSearch,
    onCreateLink: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: LinksViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    LaunchedEffect(search) {
        snapshotFlow { search.query }.collect { viewModel.onIntent(LinksIntent.QueryChanged(it)) }
    }
    // Nothing saved, nothing to search: the top bar drops its search button.
    LaunchedEffect(state.loading, state.linkCount) {
        if (!state.loading) search.updateSearchable(state.linkCount > 0)
    }
    ObserveEffects(viewModel.effects) { effect ->
        when (effect) {
            // The card's copy animation is the confirmation; Android 13+ adds its own on top.
            is LinksEffect.CopyUrl -> clipboard.setText(AnnotatedString(effect.url))
            is LinksEffect.OpenUrl -> context.openLink(effect.url)
            is LinksEffect.Share -> context.shareLink(effect.url, effect.title)
            LinksEffect.OpenCreateLink -> onCreateLink()
        }
    }
    LinksListScreen(state = state, searchExpanded = search.expanded, onIntent = viewModel::onIntent, modifier = modifier)
}

/** [searchExpanded]: the top bar's search field is open, so the + button steps aside. */
@Composable
fun LinksListScreen(
    state: LinksState,
    searchExpanded: Boolean,
    onIntent: (LinksIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        // RootScreen's Scaffold already pads for the system bars; the default insets here would
        // add the status bar height again above the header (and the nav bar below the list).
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        floatingActionButton = {
            // Out of the way while searching, when the keyboard would lift it over the results.
            AnimatedVisibility(
                visible = !searchExpanded,
                enter = scaleIn(tween(FAB_IN_MS, delayMillis = FAB_IN_DELAY_MS, easing = EmphasizedDecelerate)) +
                    fadeIn(tween(FAB_IN_MS, delayMillis = FAB_IN_DELAY_MS)),
                exit = scaleOut(tween(FAB_OUT_MS, easing = EmphasizedAccelerate), targetScale = 0.8f) +
                    fadeOut(tween(FAB_OUT_MS)),
            ) {
                FloatingActionButton(
                    onClick = { onIntent(LinksIntent.CreateLink) },
                    containerColor = Synapse,
                    contentColor = Void,
                ) {
                    Icon(Icons.Default.Add, contentDescription = "New Link")
                }
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
            when {
                state.loading -> Unit
                state.empty -> EmptyLinks()
                else -> LinksList(state = state, onIntent = onIntent)
            }
        }
    }
}

@Composable
private fun EmptyLinks() {
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
 *
 * Tags swaps the tray for the tag editor (Figma: Links / Edit tags). Edits stay in the ViewModel's
 * draft until the editor closes, by Done, back or a tap outside, and are saved then.
 */
@Composable
private fun LinksList(
    state: LinksState,
    onIntent: (LinksIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val links = state.visibleLinks
    // Ages only need minute precision; refresh them whenever the list itself changes.
    val nowMillis = remember(links) { System.currentTimeMillis() }
    // One card animates at a time: a tap anywhere restarts the animation on the tapped card.
    var copyTap by remember { mutableStateOf<CopyTap?>(null) }

    val openLinkId = state.openOptionsLinkId
    val optionsOpen = openLinkId != null
    val currentOptionsOpen by rememberUpdatedState(optionsOpen)
    val tapOutside = remember { OptionsCardBounds() }
    val focusManager = LocalFocusManager.current

    // While the tray collapses, the editor keeps showing the draft it closed with.
    val editor = remember { mutableStateOf(state) }.apply { if (state.tagDraft != null) value = state }.value
    // The new-tag field's text. Opening the editor, adding a tag or closing the field starts it empty.
    var newTagName by rememberSaveable(editor.openOptionsLinkId, editor.tagDraft?.adding) { mutableStateOf("") }

    /** Closes the tray or the tag editor; the ViewModel saves the editor's changes, a name still in the field included. */
    fun closeCard() {
        // Drops the keyboard with the tray instead of after the field leaves.
        if (state.tagDraft != null) focusManager.clearFocus()
        onIntent(LinksIntent.CloseOptions(newTagName))
    }
    val currentCloseCard by rememberUpdatedState(::closeCard)

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
                    currentCloseCard()
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
                .padding(start = 16.dp, end = 16.dp, top = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            MetaLine(
                if (state.query.isBlank()) {
                    "${countLabel(state.linkCount, "LINK")} · ${countLabel(state.tags.size, "TAG")}"
                } else {
                    resultsLabel(links.size, state.query)
                },
                modifier = Modifier.graphicsLayer { alpha = chromeAlpha },
            )
            // Registered after the top bar's search, so back closes the tray before it collapses search.
            BackHandler(enabled = optionsOpen, onBack = ::closeCard)
            if (state.tags.isNotEmpty()) {
                TagFilters(
                    tags = state.tagCounts,
                    selectedTags = state.activeTags,
                    onTagToggle = { onIntent(LinksIntent.ToggleTagFilter(it)) },
                    modifier = Modifier.graphicsLayer { alpha = chromeAlpha },
                )
            }
            LazyColumn(
                modifier = Modifier.weight(1f),
                // Extra bottom room so the FAB never covers the last card.
                contentPadding = PaddingValues(bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(links, key = { it.id }) { link ->
                    val linkId = link.id
                    val isOpen = linkId == openLinkId
                    val rowAlpha by animateFloatAsState(
                        targetValue = if (optionsOpen && !isOpen) DIMMED_ALPHA else 1f,
                        animationSpec = tween(PRESS_MS, easing = StandardEasing),
                        label = "row dim",
                    )
                    AnimatedContent(
                        targetState = state.visiblePendingDeletion?.takeIf { it.linkId == linkId },
                        transitionSpec = { fadeIn(tween(SWAP_MS)) togetherWith fadeOut(tween(SWAP_MS)) },
                        contentKey = { it != null },
                        modifier = Modifier
                            .animateItem()
                            .graphicsLayer { alpha = rowAlpha },
                        label = "link row",
                    ) { deletion ->
                        if (deletion != null) {
                            DeletedLinkRow(link = link, deletion = deletion, onUndo = { onIntent(LinksIntent.UndoDelete) })
                        } else {
                            val copyTick = copyTap?.takeIf { it.linkId == linkId }?.tick
                            LinkCard(
                                link = link,
                                nowMillis = nowMillis,
                                copyTick = copyTick,
                                optionsOpen = isOpen,
                                editingTags = isOpen && state.tagDraft != null,
                                onCopy = {
                                    copyTap = CopyTap(linkId, tick = (copyTap?.tick ?: 0) + 1)
                                    onIntent(LinksIntent.Copy(linkId))
                                },
                                // Clear once finished so a card scrolled back into view doesn't replay it.
                                onCopyFinished = { if (copyTap?.tick == copyTick) copyTap = null },
                                onLongPress = { onIntent(LinksIntent.ShowOptions(linkId)) },
                                onOpen = { onIntent(LinksIntent.Open(linkId)) },
                                onShare = { onIntent(LinksIntent.Share(linkId)) },
                                onDelete = { onIntent(LinksIntent.Delete(linkId)) },
                                onEditTags = { onIntent(LinksIntent.EditTags) },
                                tagEditor = {
                                    editor.tagDraft?.let { draft ->
                                        LinkTagEditor(
                                            tags = editor.editorTags,
                                            selectedTags = draft.tags,
                                            edited = editor.draftEdited,
                                            addingTag = draft.adding,
                                            newTagName = newTagName,
                                            onTagToggle = { onIntent(LinksIntent.ToggleDraftTag(it)) },
                                            onStartNewTag = { onIntent(LinksIntent.StartNewTag) },
                                            onNewTagNameChange = { newTagName = it },
                                            onAddTag = { onIntent(LinksIntent.AddDraftTag(newTagName)) },
                                            onDone = ::closeCard,
                                        )
                                    }
                                },
                                modifier = if (isOpen) Modifier.onGloballyPositioned { tapOutside.card = it } else Modifier,
                            )
                        }
                    }
                }
                if (links.isEmpty()) {
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

private data class CopyTap(val linkId: Long, val tick: Int)

private fun Context.openLink(url: String) {
    // Addresses can be saved without a scheme, which no browser would claim.
    val uri = Uri.parse(url).let { if (it.scheme == null) Uri.parse("https://$url") else it }
    try {
        startActivity(Intent(Intent.ACTION_VIEW, uri))
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(this, "No app can open this link", Toast.LENGTH_SHORT).show()
    }
}

private fun Context.shareLink(url: String, title: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, url)
        if (title.isNotBlank()) putExtra(Intent.EXTRA_SUBJECT, title)
    }
    startActivity(Intent.createChooser(send, "Share link"))
}

// + leaves with the top bar's first search phase and returns once the field has closed.
private const val FAB_OUT_MS = 150
private const val FAB_IN_MS = 200
private const val FAB_IN_DELAY_MS = 150

// ── Previews ──────────────────────────────────────────────────────

@Preview
@Composable
private fun LinksListPreview() {
    val now = System.currentTimeMillis()
    val day = 24 * 60 * 60 * 1000L
    KortexTheme {
        Box(Modifier.background(Void)) {
            LinksListScreen(
                state = LinksState(
                    loading = false,
                    links = listOf(
                        Link(1, "https://curaahome.com/products/curaa-automatic-pepper-grinder", "Auto pepper grinder", now - 3 * day, thumbnailPath = null, tags = listOf("to buy")),
                        Link(2, "https://www.google.com", "Google website", now - 14 * day, thumbnailPath = null, tags = listOf("sample")),
                    ),
                    tags = listOf(TagCount("sample", 1), TagCount("ticket", 0), TagCount("to buy", 1)),
                    pendingDeletion = PendingLinkDeletion(1, startedAtMillis = now, deadlineMillis = now + 5_000),
                ),
                searchExpanded = false,
                onIntent = {},
            )
        }
    }
}
