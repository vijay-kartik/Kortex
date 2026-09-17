package dev.kortex.app.ui.screens.home

import androidx.activity.compose.BackHandler
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import dev.kortex.links.ui.CreateLinkScreen
import dev.kortex.links.ui.LinksScreen

/**
 * Stateful entry to the home UI. Owns [RootState] and onboarding; every screen it shows gets
 * its own ViewModel, and everything it draws goes through [RootContent] as slots.
 */
@Composable
fun RootScreen(
    entryRequest: EntryRequest? = null,
    onEntryRequestHandled: () -> Unit = {},
) {
    val state = rememberRootState()
    val homeVm: HomeViewModel = hiltViewModel()
    val onboardingSeen by homeVm.onboardingSeen.collectAsStateWithLifecycle()

    EntryRequestEffect(entryRequest, state, onEntryRequestHandled)

    // CreateLinkScreen registers its own BackHandler; Settings has none, so close it here.
    BackHandler(enabled = state.overlay == Overlay.Settings, onBack = state::closeOverlay)

    RootContent(
        state = state,
        onboarding = !onboardingSeen && state.expanded == TabCategory.MyInfo,
        onOnboardingDone = {
            homeVm.markOnboardingSeen()
            state.openTab(KortexTab.Links)
        },
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
            }
        },
    )
}

/**
 * Stateless home shell: app bar, category tabs, onboarding and the switch between the tabbed
 * UI and a full-screen [Overlay]. Screen bodies come from [overlayContent] and [tabContent];
 * [tabActions] adds app-bar actions for the selected tab.
 */
@Composable
fun RootContent(
    state: RootState,
    onboarding: Boolean,
    onOnboardingDone: () -> Unit,
    tabActions: @Composable (KortexTab) -> Unit,
    overlayContent: @Composable (Overlay) -> Unit,
    tabContent: @Composable (KortexTab) -> Unit,
) {
    when (val overlay = state.overlay) {
        Overlay.None -> Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                // KortexAppBar emits the app bar and the tab bar as siblings; stack them.
                Column {
                    KortexAppBar(
                        selected = state.selected,
                        expanded = state.expanded,
                        onboarding = onboarding,
                        actions = { tabActions(state.selected) },
                        onMcpSettingsClick = state::openSettings,
                        onCategorySelected = state::openCategory,
                        onTabSelected = state::openTab,
                    )
                }
            },
        ) { innerPadding ->
            Box(Modifier.padding(innerPadding)) {
                if (onboarding) {
                    MyInfoOnboarding(onOpenLinks = onOnboardingDone)
                } else {
                    tabContent(state.selected)
                }
            }
        }
        else -> overlayContent(overlay)
    }
}

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
            onboarding = false,
            onOnboardingDone = {},
            tabActions = {},
            overlayContent = { PreviewSlot(it.toString()) },
            tabContent = { PreviewSlot(it.label) },
        )
    }
}

@Preview
@Composable
private fun RootContentOnboardingPreview() {
    KortexTheme {
        RootContent(
            state = remember { RootState(selected = KortexTab.Links) },
            onboarding = true,
            onOnboardingDone = {},
            tabActions = {},
            overlayContent = { PreviewSlot(it.toString()) },
            tabContent = { PreviewSlot(it.label) },
        )
    }
}
