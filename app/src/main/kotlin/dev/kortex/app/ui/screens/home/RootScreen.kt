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
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.kortex.app.ChatViewModel
import dev.kortex.app.R
import dev.kortex.app.RunsScreen
import dev.kortex.app.SettingsScreen
import dev.kortex.app.ui.appbar.KortexAppBar
import dev.kortex.app.ui.screens.ChatScreen
import dev.kortex.app.ui.screens.GraphScreen
import dev.kortex.app.ui.screens.HistoryScreen
import dev.kortex.app.ui.screens.links.CreateLinkScreen
import dev.kortex.app.ui.screens.links.LinksScreen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RootScreen(
    requestedSessionId: String? = null,
    onSessionRequestConsumed: () -> Unit = {},
) {
    var selected by rememberSaveable { mutableStateOf(KortexTab.Links) }
    var expanded by rememberSaveable { mutableStateOf(TabCategory.MyInfo) }
    // Coming back to Agent restores the leaf the user left, rather than resetting to Chat.
    var lastAgentTab by rememberSaveable { mutableStateOf(KortexTab.Chat) }
    var showSettings by remember { mutableStateOf(false) }
    var showCreateLinks by rememberSaveable { mutableStateOf(false) }
    val vm: ChatViewModel = viewModel()
    val chatUi by vm.ui.collectAsStateWithLifecycle()
    val homeVm: HomeViewModel = viewModel()
    val onboardingSeen by homeVm.onboardingSeen.collectAsStateWithLifecycle()
    val onboarding = !onboardingSeen && expanded == TabCategory.MyInfo

    fun openTab(tab: KortexTab) {
        selected = tab
        expanded = tab.category
        if (tab.category == TabCategory.Agent) lastAgentTab = tab
    }

    // Notification tap → load that session on the Chat tab.
    LaunchedEffect(requestedSessionId) {
        if (requestedSessionId != null) {
            vm.loadSession(requestedSessionId)
            openTab(KortexTab.Chat)
            onSessionRequestConsumed()
        }
    }

    when {
        showSettings -> SettingsScreen(onDismiss = { showSettings = false })
        showCreateLinks -> CreateLinkScreen({ showCreateLinks = false })
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
                            KortexTab.Graph -> GraphScreen(vm = viewModel())
                            KortexTab.History -> HistoryScreen(
                                vm = vm,
                                onSelectSession = { sessionId ->
                                    vm.loadSession(sessionId)
                                    openTab(KortexTab.Chat)
                                }
                            )

                            KortexTab.Runs -> RunsScreen()
                            KortexTab.Links -> LinksScreen(onCreateLink = { showCreateLinks = true })
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
