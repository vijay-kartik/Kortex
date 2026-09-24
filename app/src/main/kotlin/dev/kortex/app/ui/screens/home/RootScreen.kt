package dev.kortex.app.ui.screens.home

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import dev.kortex.app.ui.appbar.KortexAppBar
import dev.kortex.app.ui.screens.chat.ChatRequest
import dev.kortex.app.ui.screens.chat.ChatScreen
import dev.kortex.app.ui.screens.chat.ChatShareAction
import dev.kortex.app.ui.screens.chat.HistoryScreen
import dev.kortex.app.ui.screens.graph.GraphScreen
import dev.kortex.app.ui.screens.runs.RunsScreen
import dev.kortex.app.ui.screens.settings.SettingsScreen
import dev.kortex.design.KortexTheme
import dev.kortex.design.Muted
import dev.kortex.design.Panel
import dev.kortex.design.anim.EmphasizedDecelerate
import dev.kortex.design.anim.StandardEasing
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

    EntryRequestEffect(entryRequest, state, onEntryRequestHandled)

    // CreateLinkScreen and the Topics routes register their own BackHandler; Settings has none, so close it here.
    BackHandler(enabled = state.overlay == Overlay.Settings, onBack = state::closeOverlay)

    RootContent(
        state = state,
        tabActions = { tab ->
            if (tab == KortexTab.Chat) ChatShareAction()
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
                KortexTab.Links -> LinksScreen(onCreateLink = { state.openCreateLink() })
                KortexTab.Topics -> TopicsScreen(
                    onCreateTopic = state::openNewTopic,
                    onOpenTopic = state::openTopic,
                )
            }
        },
    )
}

/**
 * Stateless home shell: app bar, category tabs and the switch between the tabbed
 * UI and a full-screen [Overlay]. Screen bodies come from [overlayContent] and [tabContent];
 * [tabActions] adds app-bar actions for the selected tab.
 */
@Composable
fun RootContent(
    state: RootState,
    tabActions: @Composable (KortexTab) -> Unit,
    overlayContent: @Composable (Overlay) -> Unit,
    tabContent: @Composable (KortexTab) -> Unit,
) {
    AnimatedContent(
        targetState = state.overlay,
        transitionSpec = { overlayTransition() },
        label = "overlay",
    ) { overlay ->
        when (overlay) {
            Overlay.None -> Scaffold(
                containerColor = MaterialTheme.colorScheme.background,
                topBar = {
                    // KortexAppBar emits the app bar and the tab bar as siblings; stack them.
                    Column {
                        KortexAppBar(
                            selected = state.selected,
                            expanded = state.expanded,
                            actions = { tabActions(state.selected) },
                            onMcpSettingsClick = state::openSettings,
                            onCategorySelected = state::openCategory,
                            onTabSelected = state::openTab,
                        )
                    }
                },
            ) { innerPadding ->
                Box(Modifier.padding(innerPadding)) {
                    tabContent(state.selected)
                }
            }
            else -> overlayContent(overlay)
        }
    }
}

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
            tabActions = {},
            overlayContent = { PreviewSlot(it.toString()) },
            tabContent = { PreviewSlot(it.label) },
        )
    }
}
