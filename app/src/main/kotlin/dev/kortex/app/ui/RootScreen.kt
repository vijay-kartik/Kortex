package dev.kortex.app.ui

import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.kortex.app.ChatViewModel
import dev.kortex.app.HistoryScreen
import dev.kortex.app.R
import dev.kortex.app.RunsScreen
import dev.kortex.app.SettingsScreen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RootScreen(
    requestedSessionId: String? = null,
    onSessionRequestConsumed: () -> Unit = {},
) {
    var tab by remember { mutableIntStateOf(0) }
    var showSettings by remember { mutableStateOf(false) }
    val tabs = listOf("Chat", "Graph", "History", "Runs")
    val vm: ChatViewModel = viewModel()
    val chatUi by vm.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Notification tap → load that session on the Chat tab.
    LaunchedEffect(requestedSessionId) {
        if (requestedSessionId != null) {
            vm.loadSession(requestedSessionId)
            tab = 0
            onSessionRequestConsumed()
        }
    }

    if (showSettings) {
        SettingsScreen(onDismiss = { showSettings = false })
    } else {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                Column {
                    TopAppBar(
                        title = { Wordmark() },
                        actions = {
                            if (tab == 0) {
                                if (chatUi.turns.isNotEmpty()) {
                                    IconButton(onClick = {
                                        val send = Intent(Intent.ACTION_SEND).apply {
                                            type = "text/plain"
                                            putExtra(Intent.EXTRA_TEXT, conversationAsText(chatUi.turns))
                                        }
                                        context.startActivity(Intent.createChooser(send, "Share conversation"))
                                    }) {
                                        Icon(
                                            painterResource(R.drawable.ic_share),
                                            contentDescription = "Share conversation",
                                            tint = Muted,
                                        )
                                    }
                                }
                            }
                            IconButton(onClick = { showSettings = true }) {
                                Icon(
                                    painterResource(R.drawable.ic_tune),
                                    contentDescription = "MCP Settings",
                                    tint = Muted,
                                )
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.background,
                        ),
                    )
                    ScrollableTabRow(
                        selectedTabIndex = tab,
                        containerColor = MaterialTheme.colorScheme.background,
                        edgePadding = 8.dp,
                        divider = {},
                    ) {
                        tabs.forEachIndexed { i, title ->
                            Tab(
                                selected = tab == i,
                                onClick = { tab = i },
                                selectedContentColor = Synapse,
                                unselectedContentColor = Muted,
                                text = { Text(title, style = MaterialTheme.typography.labelLarge) },
                            )
                        }
                    }
                }
            },
        ) { innerPadding ->
            Box(Modifier.padding(innerPadding)) {
                when (tab) {
                    0 -> ChatScreen(vm = vm)
                    1 -> GraphScreen(vm = viewModel())
                    2 -> HistoryScreen(
                        vm = vm,
                        onSelectSession = { sessionId ->
                            vm.loadSession(sessionId)
                            tab = 0
                        }
                    )
                    else -> RunsScreen()
                }
            }
        }
    }
}

/** Status light + tracked-out wordmark: the app reads as an instrument, not a toy. */
@Composable
private fun Wordmark() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
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
