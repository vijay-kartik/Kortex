package dev.kortex.app.ui.screens.home

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.tooling.preview.Preview
import dev.kortex.app.ui.navigation.HomeMenu
import dev.kortex.app.ui.screens.chat.ChatRequest
import dev.kortex.app.ui.screens.chat.ChatScreen
import dev.kortex.app.ui.screens.chat.ChatShareAction
import dev.kortex.app.ui.screens.chat.HistoryScreen
import dev.kortex.app.ui.screens.graph.GraphScreen
import dev.kortex.app.ui.screens.runs.RunsScreen
import dev.kortex.app.ui.screens.settings.SettingsScreen
import dev.kortex.design.KortexTheme
import dev.kortex.design.KortexTopBar
import dev.kortex.design.Muted
import dev.kortex.design.Panel
import dev.kortex.design.TopBarSearch
import dev.kortex.design.anim.EmphasizedAccelerate
import dev.kortex.design.anim.EmphasizedDecelerate
import dev.kortex.design.anim.StandardEasing
import dev.kortex.design.rememberTopBarSearch
import dev.kortex.links.ui.CreateLinkScreen
import dev.kortex.links.ui.LinksScreen
import dev.kortex.myinfo.topics.ui.TopicsScreen
import dev.kortex.myinfo.topics.ui.create.NewTopicRoute
import dev.kortex.myinfo.topics.ui.detail.TopicDetailRoute

/**
 * Stateful entry to the home UI. Owns [RootState]; every screen it shows gets
 * its own ViewModel, and everything it draws goes through [RootContent] as slots.
 */
@Composable
fun RootScreen(
    entryRequest: EntryRequest? = null,
    onEntryRequestHandled: () -> Unit = {},
) {
    val state = rememberRootState()
    // Held here, not in the tabs, so a query survives switching away and opening a topic from its results.
    val linksSearch = rememberTopBarSearch()
    val topicsSearch = rememberTopBarSearch()

    EntryRequestEffect(entryRequest, state, onEntryRequestHandled)

    // CreateLinkScreen and the Topics routes register their own BackHandler; Settings has none, so close it here.
    BackHandler(enabled = state.overlay == Overlay.Settings, onBack = state::closeOverlay)

    RootContent(
        state = state,
        tabSearch = { tab ->
            when (tab) {
                KortexTab.Links -> linksSearch
                KortexTab.Topics -> topicsSearch
                else -> null
            }
        },
        tabActions = { tab ->
            if (tab == KortexTab.Chat) ChatShareAction()
        },
        menu = {
            HomeMenu(
                selected = state.selected,
                onSelect = state::openTab,
                onOpenSettings = state::openSettings,
                onClose = state::closeMenu,
            )
        },
        overlayContent = { overlay ->
            when (overlay) {
                Overlay.None -> Unit
                Overlay.Settings -> SettingsScreen(onDismiss = state::closeOverlay)
                // Keyed on the address so a new share replaces a half-filled form instead of merging into it.
                is Overlay.CreateLink -> key(overlay.url) {
                    CreateLinkScreen(onBack = state::closeOverlay, initialUrl = overlay.url)
                }
                Overlay.NewTopic -> NewTopicRoute(onClose = state::closeOverlay, onCreated = state::openTopic)
                // Keyed so opening another topic starts that topic's screen rather than reusing this one.
                is Overlay.Topic -> key(overlay.topicId) {
                    TopicDetailRoute(topicId = overlay.topicId, onClose = state::closeOverlay)
                }
            }
        },
        tabContent = { tab ->
            when (tab) {
                KortexTab.Chat -> ChatScreen(
                    request = state.pendingChatRequest,
                    onRequestHandled = state::onChatRequestHandled,
                )
                KortexTab.Graph -> GraphScreen()
                KortexTab.History -> HistoryScreen(
                    onSelectSession = { sessionId -> state.openChat(ChatRequest.LoadSession(sessionId)) },
                    onNewChat = { state.openChat(ChatRequest.NewSession()) },
                )
                KortexTab.Runs -> RunsScreen()
                KortexTab.Links -> LinksScreen(search = linksSearch, onCreateLink = { state.openCreateLink() })
                KortexTab.Topics -> TopicsScreen(
                    search = topicsSearch,
                    onCreateTopic = state::openNewTopic,
                    onOpenTopic = state::openTopic,
                )
            }
        },
    )
}

/**
 * Stateless home shell (Figma: Home Navigation): the top bar, the full-width [menu] sliding over the
 * tabs, and the switch between the tabbed UI and a full-screen [Overlay]. Screen bodies come from
 * [overlayContent] and [tabContent]; a tab gets the bar's search from [tabSearch], or puts
 * [tabActions] in its place.
 */
@Composable
fun RootContent(
    state: RootState,
    tabSearch: (KortexTab) -> TopBarSearch?,
    tabActions: @Composable (KortexTab) -> Unit,
    menu: @Composable () -> Unit,
    overlayContent: @Composable (Overlay) -> Unit,
    tabContent: @Composable (KortexTab) -> Unit,
) {
    AnimatedContent(
        targetState = state.overlay,
        transitionSpec = { overlayTransition() },
        label = "overlay",
    ) { overlay ->
        when (overlay) {
            Overlay.None -> Box(Modifier.fillMaxSize()) {
                // A fresh tab starts at its top, so the hairline starts hidden.
                val scroll = remember(state.selected) { ScrolledUnder() }
                val search = tabSearch(state.selected)
                Scaffold(
                    containerColor = MaterialTheme.colorScheme.background,
                    topBar = {
                        KortexTopBar(
                            title = state.selected.label,
                            onMenuClick = state::openMenu,
                            search = search,
                            searchHint = "Search ${state.selected.label.lowercase()}",
                            scrolledUnder = scroll.scrolledUnder,
                            action = { tabActions(state.selected) },
                        )
                    },
                ) { innerPadding ->
                    Box(Modifier.padding(innerPadding).nestedScroll(scroll)) {
                        tabContent(state.selected)
                    }
                }
                AnimatedVisibility(
                    visible = state.menuOpen,
                    enter = slideInHorizontally(tween(MENU_OPEN_MS, easing = EmphasizedDecelerate)) { -it },
                    exit = slideOutHorizontally(tween(MENU_CLOSE_MS, easing = EmphasizedAccelerate)) { -it },
                    label = "menu",
                ) {
                    menu()
                }
            }
            else -> overlayContent(overlay)
        }
    }
}

/**
 * Fed by nested scroll from whatever list the tab shows; true while content sits scrolled under
 * the top bar, so the bar can draw its hairline.
 */
private class ScrolledUnder : NestedScrollConnection {
    private var offset by mutableFloatStateOf(0f)

    // Derived, so the bar recomposes when this flips rather than on every scroll frame.
    val scrolledUnder: Boolean by derivedStateOf { offset < -1f }

    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
        offset = (offset + consumed.y).coerceAtMost(0f)
        return Offset.Zero
    }
}

private const val MENU_OPEN_MS = 320
private const val MENU_CLOSE_MS = 250

/**
 * How one screen gives way to the next. The Topics screens push in from the end and fall back the
 * same way, and one Topics screen replacing another (a new topic opening once saved) crossfades.
 * Every other overlay keeps switching instantly, as it always has.
 */
private fun AnimatedContentTransitionScope<Overlay>.overlayTransition(): ContentTransform {
    val from = initialState
    val to = targetState
    if (!from.isTopics && !to.isTopics) return EnterTransition.None togetherWith ExitTransition.None
    return when {
        from == Overlay.None -> (
            slideInHorizontally(tween(OVERLAY_ENTER_MS, easing = EmphasizedDecelerate)) { it / OVERLAY_SLIDE_FRACTION } +
                fadeIn(tween(OVERLAY_ENTER_MS, easing = EmphasizedDecelerate))
            ) togetherWith fadeOut(tween(OVERLAY_EXIT_MS, easing = StandardEasing))
        // The screen being closed stays on top while it slides away, so the tabs appear beneath it.
        to == Overlay.None -> (
            fadeIn(tween(OVERLAY_ENTER_MS, easing = StandardEasing)) togetherWith (
                slideOutHorizontally(tween(OVERLAY_EXIT_MS, easing = StandardEasing)) { it / OVERLAY_SLIDE_FRACTION } +
                    fadeOut(tween(OVERLAY_EXIT_MS, easing = StandardEasing))
                )
            ).apply { targetContentZIndex = -1f }
        else -> fadeIn(tween(OVERLAY_ENTER_MS, easing = StandardEasing)) togetherWith
            fadeOut(tween(OVERLAY_EXIT_MS, easing = StandardEasing))
    }
}

/** The full-screen Topics screens: the new-topic form and a topic's feed. */
private val Overlay.isTopics: Boolean
    get() = when (this) {
        Overlay.NewTopic, is Overlay.Topic -> true
        else -> false
    }

private const val OVERLAY_ENTER_MS = 300
private const val OVERLAY_EXIT_MS = 200

/** A nudge rather than a full-width slide: the screen arrives from a little way off, not the edge. */
private const val OVERLAY_SLIDE_FRACTION = 8

/** Acts on a notification tap, launcher shortcut or share once, then clears the request. */
@Composable
private fun EntryRequestEffect(
    request: EntryRequest?,
    state: RootState,
    onHandled: () -> Unit,
) {
    LaunchedEffect(request) {
        when (val pending = request ?: return@LaunchedEffect) {
            // Load that session on the Chat tab.
            is EntryRequest.OpenSession -> state.openChat(ChatRequest.LoadSession(pending.sessionId))
            // New-link screen, address filled in when there is one.
            is EntryRequest.NewLink -> {
                state.openTab(KortexTab.Links)
                state.openCreateLink(pending.url)
            }
            // A new chat with the draft in the composer, not sent.
            is EntryRequest.NewChat -> {
                state.closeOverlay()
                state.openChat(ChatRequest.NewSession(draft = pending.draft))
            }
        }
        onHandled()
    }
}

// ── Previews ──────────────────────────────────────────────────────

@Composable
private fun PreviewSlot(label: String) {
    Box(Modifier.fillMaxSize().background(Panel), contentAlignment = Alignment.Center) {
        Text(label, color = Muted)
    }
}

@Preview
@Composable
private fun RootContentChatPreview() {
    KortexTheme {
        RootContent(
            state = remember { RootState(selected = KortexTab.Chat) },
            tabSearch = { null },
            tabActions = {},
            menu = {},
            overlayContent = { PreviewSlot(it.toString()) },
            tabContent = { PreviewSlot(it.label) },
        )
    }
}
