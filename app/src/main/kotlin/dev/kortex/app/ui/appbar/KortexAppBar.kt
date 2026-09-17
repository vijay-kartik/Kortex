package dev.kortex.app.ui.appbar

import android.content.Intent
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import dev.kortex.app.ui.screens.chat.ChatUi
import dev.kortex.design.R
import dev.kortex.design.Muted
import dev.kortex.app.ui.screens.home.KortexTab
import dev.kortex.app.ui.screens.home.TabCategory
import dev.kortex.app.ui.screens.home.Wordmark
import dev.kortex.app.ui.util.conversationAsText

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KortexAppBar(
    selected: KortexTab,
    expanded: TabCategory,
    onboarding: Boolean,
    chatUi: ChatUi,
    onMcpSettingsClick: () -> Unit,
    onCategorySelected: (TabCategory) -> Unit,
    onTabSelected: (KortexTab) -> Unit,
) {
    val context = LocalContext.current
    TopAppBar(
        title = { Wordmark() },
        actions = {
            if (selected == KortexTab.Chat) {
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
    CategoryTabBar(
        selected = selected,
        expanded = expanded,
        onboarding = onboarding,
        onCategorySelected = onCategorySelected,
        onTabSelected = onTabSelected,
    )
}