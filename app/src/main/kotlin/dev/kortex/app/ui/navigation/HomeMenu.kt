package dev.kortex.app.ui.navigation

import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kortex.app.ui.screens.home.KortexTab
import dev.kortex.app.ui.screens.home.TabCategory
import dev.kortex.design.Alarm
import dev.kortex.design.CircleButton
import dev.kortex.design.Edge
import dev.kortex.design.Ink
import dev.kortex.design.Muted
import dev.kortex.design.Panel
import dev.kortex.design.R
import dev.kortex.design.Synapse
import dev.kortex.design.SynapseDim
import dev.kortex.design.Void
import dev.kortex.sync.CloudUser

/**
 * Full-width home menu (Figma: Home Navigation 02, 07–08): every section under Agent and My Info,
 * then the account card with Tools and settings and Log out. RootContent slides it over the tabs.
 */
@Composable
fun HomeMenu(
    selected: KortexTab,
    onSelect: (KortexTab) -> Unit,
    onOpenSettings: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    account: AccountViewModel = hiltViewModel(),
) {
    val user by account.user.collectAsStateWithLifecycle()
    var confirmLogOut by rememberSaveable { mutableStateOf(false) }
    BackHandler(onBack = onClose)

    Column(
        modifier
            .fillMaxSize()
            .background(Void)
            // A hit target of its own, so taps never fall through to the tab underneath.
            .pointerInput(Unit) { awaitEachGesture { awaitFirstDown(requireUnconsumed = false) } }
            .systemBarsPadding(),
    ) {
        Box(Modifier.fillMaxWidth().height(56.dp)) {
            // Where the menu button was, so opening and closing happen under the same thumb.
            CircleButton(
                icon = R.drawable.ic_close,
                contentDescription = "Close menu",
                onClick = onClose,
                modifier = Modifier.align(Alignment.CenterStart).padding(start = 16.dp),
            )
            Wordmark(Modifier.align(Alignment.CenterStart).padding(start = 72.dp))
        }
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            TabCategory.entries.forEachIndexed { index, category ->
                SectionLabel(
                    category.label.uppercase(),
                    Modifier.padding(start = 8.dp, top = if (index == 0) 4.dp else 16.dp),
                )
                KortexTab.of(category).forEach { tab ->
                    NavRow(tab, selected = tab == selected, onClick = { onSelect(tab) })
                }
            }
        }
        AccountCard(
            user = user,
            onOpenSettings = onOpenSettings,
            onLogOut = { confirmLogOut = true },
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 20.dp),
        )
    }

    if (confirmLogOut) {
        AlertDialog(
            onDismissRequest = { confirmLogOut = false },
            containerColor = Panel,
            title = { Text("Log out of Kortex?") },
            text = {
                Text(
                    "You’ll need to sign in again to use Kortex. Your links and topics stay on this phone.",
                    color = Muted,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmLogOut = false
                        account.logOut()
                    },
                ) { Text("Log out", color = Alarm) }
            },
            dismissButton = {
                TextButton(onClick = { confirmLogOut = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun Wordmark(modifier: Modifier = Modifier) {
    Row(
        modifier.semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Image(painterResource(R.drawable.ic_kortex_mark), contentDescription = null, modifier = Modifier.size(18.dp))
        Text(
            "KORTEX",
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 4.sp),
            color = Ink,
        )
    }
}

/** Same voice as the Settings section labels. */
@Composable
private fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.2.sp),
        color = Muted,
        modifier = modifier.padding(bottom = 8.dp).semantics { heading() },
    )
}

@Composable
private fun NavRow(tab: KortexTab, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) SynapseDim else Color.Transparent)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.size(32.dp).background(if (selected) Color.Transparent else Panel, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painterResource(tab.icon),
                contentDescription = null,
                tint = if (selected) Synapse else Muted,
                modifier = Modifier.size(18.dp),
            )
        }
        Text(
            tab.label,
            style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp),
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            color = if (selected) Synapse else Ink,
        )
    }
}

@Composable
private fun AccountCard(
    user: CloudUser?,
    onOpenSettings: () -> Unit,
    onLogOut: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(12.dp)
    val label = user?.name ?: user?.email ?: "Google account"
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Panel)
            .border(1.dp, Edge, shape),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(Modifier.size(36.dp).background(SynapseDim, CircleShape), contentAlignment = Alignment.Center) {
                Text(label.first().uppercase(), color = Synapse, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.titleSmall, color = Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val email = user?.email
                if (email != null && email != label) {
                    Text(email, style = MaterialTheme.typography.bodySmall, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        HorizontalDivider(color = Edge)
        CardAction(R.drawable.ic_tune, "Tools and settings", color = Ink, iconTint = Muted, onClick = onOpenSettings)
        HorizontalDivider(color = Edge)
        CardAction(R.drawable.ic_logout, "Log out", color = Alarm, iconTint = Alarm, emphasis = true, onClick = onLogOut)
    }
}

@Composable
private fun CardAction(
    @DrawableRes icon: Int,
    label: String,
    color: Color,
    iconTint: Color,
    onClick: () -> Unit,
    emphasis: Boolean = false,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = iconTint, modifier = Modifier.size(18.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (emphasis) FontWeight.Medium else FontWeight.Normal,
            color = color,
        )
    }
}

@get:DrawableRes
private val KortexTab.icon: Int
    get() = when (this) {
        KortexTab.Chat -> R.drawable.ic_chat
        KortexTab.Graph -> R.drawable.ic_share_nodes
        KortexTab.History -> R.drawable.ic_history
        KortexTab.Runs -> R.drawable.ic_timeline
        KortexTab.Links -> R.drawable.ic_link
        KortexTab.Topics -> R.drawable.ic_layers
    }
