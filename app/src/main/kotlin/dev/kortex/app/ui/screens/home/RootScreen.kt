package dev.kortex.app.ui.screens.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.hilt.navigation.compose.hiltViewModel
import dev.kortex.app.ui.screens.chat.ChatViewModel
import dev.kortex.design.R
import dev.kortex.app.ui.screens.runs.RunsScreen
import dev.kortex.app.ui.screens.settings.SettingsScreen
import dev.kortex.app.ui.appbar.KortexAppBar
import dev.kortex.app.ui.screens.chat.ChatScreen
import dev.kortex.app.ui.screens.graph.GraphScreen
import dev.kortex.app.ui.screens.chat.HistoryScreen
import dev.kortex.links.ui.CreateLinkScreen
import dev.kortex.links.ui.LinksScreen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RootScreen(
    entryRequest: EntryRequest? = null,
    onEntryRequestHandled: () -> Unit = {},
) {
    var selected by rememberSaveable { mutableStateOf(KortexTab.Links) }
    var expanded by rememberSaveable { mutableStateOf(TabCategory.MyInfo) }
    // Coming back to Agent restores the leaf the user left, rather than resetting to Chat.
    var lastAgentTab by rememberSaveable { mutableStateOf(KortexTab.Chat) }
    var showSettings by remember { mutableStateOf(false) }
    var showCreateLinks by rememberSaveable { mutableStateOf(false) }
    // Address to pre-fill in the new-link screen; empty when opened from the Links tab.
    var createLinkUrl by rememberSaveable { mutableStateOf("") }
    val vm: ChatViewModel = hiltViewModel()
    val chatUi by vm.ui.collectAsStateWithLifecycle()
    val homeVm: HomeViewModel = hiltViewModel()
    val onboardingSeen by homeVm.onboardingSeen.collectAsStateWithLifecycle()
    val onboarding = !onboardingSeen && expanded == TabCategory.MyInfo

    fun openTab(tab: KortexTab) {
        selected = tab
        expanded = tab.category
        if (tab.category == TabCategory.Agent) lastAgentTab = tab
    }

    // Notification taps, launcher shortcuts and shares: act once, then clear the request.
    LaunchedEffect(entryRequest) {
        when (val request = entryRequest ?: return@LaunchedEffect) {
            // Load that session on the Chat tab.
            is EntryRequest.OpenSession -> {
                vm.loadSession(request.sessionId)
                openTab(KortexTab.Chat)
            }
            // New-link screen, address filled in when there is one.
            is EntryRequest.NewLink -> {
                showSettings = false
                openTab(KortexTab.Links)
                createLinkUrl = request.url
                showCreateLinks = true
            }
            // A new chat with the draft in the composer, not sent.
            is EntryRequest.NewChat -> {
                showSettings = false
                showCreateLinks = false
                vm.startNewSession(draft = request.draft)
                openTab(KortexTab.Chat)
            }
        }
        onEntryRequestHandled()
    }

    when {
        showSettings -> SettingsScreen(onDismiss = { showSettings = false })
        // Keyed on the address so a new share replaces a half-filled form instead of merging into it.
        showCreateLinks -> key(createLinkUrl) {
            CreateLinkScreen(onBack = { showCreateLinks = false }, initialUrl = createLinkUrl)
        }
        else -> {
            Scaffold(
                containerColor = MaterialTheme.colorScheme.background,
                topBar = {
                    Column {
                        KortexAppBar(
                            selected = selected,
                            expanded = expanded,
                            onboarding = onboarding,
                            chatUi = chatUi,
                            onMcpSettingsClick = { showSettings = true },
                            onCategorySelected = { category ->
                                expanded = category
                                selected = when (category) {
                                    TabCategory.Agent -> lastAgentTab
                                    TabCategory.MyInfo -> KortexTab.Links
                                }
                            },
                            onTabSelected = ::openTab,
                        )
                    }
                },
            ) { innerPadding ->
                Box(Modifier.padding(innerPadding)) {
                    when {
                        onboarding -> MyInfoOnboarding(
                            onOpenLinks = {
                                homeVm.markOnboardingSeen()
                                openTab(KortexTab.Links)
                            }
                        )

                        else -> when (selected) {
                            KortexTab.Chat -> ChatScreen(vm = vm)
                            KortexTab.Graph -> GraphScreen(vm = hiltViewModel())
                            KortexTab.History -> HistoryScreen(
                                vm = vm,
                                onSelectSession = { sessionId ->
                                    vm.loadSession(sessionId)
                                    openTab(KortexTab.Chat)
                                }
                            )

                            KortexTab.Runs -> RunsScreen()
                            KortexTab.Links -> LinksScreen(
                                onCreateLink = {
                                    createLinkUrl = ""
                                    showCreateLinks = true
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Status light + tracked-out wordmark: the app reads as an instrument, not a toy. */
@Composable
fun Wordmark() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Image(
            painterResource(R.drawable.ic_kortex_mark),
            contentDescription = null,
            modifier = Modifier.size(18.dp),
        )
        Text(
            "KORTEX",
            style = MaterialTheme.typography.titleMedium.copy(
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 4.sp,
            ),
        )
    }
}
