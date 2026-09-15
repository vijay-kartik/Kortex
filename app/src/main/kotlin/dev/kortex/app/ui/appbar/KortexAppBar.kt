package dev.kortex.app.ui.appbar

import android.content.Intent
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import dev.kortex.app.ChatUi
import dev.kortex.app.R
import dev.kortex.app.ui.Muted
import dev.kortex.app.ui.Synapse
import dev.kortex.app.ui.screens.Wordmark
import dev.kortex.app.ui.conversationAsText

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KortexAppBar(tab: Int, chatUi: ChatUi, onMcpSettingsClick: () -> Unit, onTabSelected: (Int) -> Unit) {
    val context = LocalContext.current
    val tabs = listOf("Chat", "Graph", "History", "Runs", "Links")
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
            IconButton(onClick = onMcpSettingsClick) {
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
                onClick = { onTabSelected(i) },
                selectedContentColor = Synapse,
                unselectedContentColor = Muted,
                text = { Text(title, style = MaterialTheme.typography.labelLarge) },
            )
        }
    }
}