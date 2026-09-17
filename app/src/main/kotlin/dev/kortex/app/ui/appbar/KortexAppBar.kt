package dev.kortex.app.ui.appbar

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kortex.app.ui.screens.home.KortexTab
import dev.kortex.app.ui.screens.home.TabCategory
import dev.kortex.design.Muted
import dev.kortex.design.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KortexAppBar(
    selected: KortexTab,
    expanded: TabCategory,
    onboarding: Boolean,
    /** Shows the share-conversation action when non-null. */
    onShareConversation: (() -> Unit)?,
    onMcpSettingsClick: () -> Unit,
    onCategorySelected: (TabCategory) -> Unit,
    onTabSelected: (KortexTab) -> Unit,
) {
    TopAppBar(
        title = { Wordmark() },
        actions = {
            if (onShareConversation != null) {
                IconButton(onClick = onShareConversation) {
                    Icon(
                        painterResource(R.drawable.ic_share),
                        contentDescription = "Share conversation",
                        tint = Muted,
                    )
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