package dev.kortex.app.ui.screens

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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import dev.kortex.app.ui.screens.links.LinksScreen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RootScreen(
    requestedSessionId: String? = null,
    onSessionRequestConsumed: () -> Unit = {},
) {
    var tab by remember { mutableIntStateOf(0) }
    var showSettings by remember { mutableStateOf(false) }
    val vm: ChatViewModel = viewModel()
    val chatUi by vm.ui.collectAsStateWithLifecycle()

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
                    KortexAppBar(
                        tab,
                        chatUi,
                        { showSettings = true },
                        { i -> tab = i }
                    )
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
                    3 -> RunsScreen()
                    else -> LinksScreen()
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
