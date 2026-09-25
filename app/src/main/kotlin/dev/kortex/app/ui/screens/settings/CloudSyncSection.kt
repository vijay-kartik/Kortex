package dev.kortex.app.ui.screens.settings

import android.text.format.DateUtils
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kortex.design.Edge
import dev.kortex.design.Ink
import dev.kortex.design.Muted
import dev.kortex.design.Panel
import dev.kortex.design.R
import dev.kortex.design.Synapse
import dev.kortex.design.SynapseDim

/** Tools & Settings › CLOUD SYNC: the account links and topics back up to, and Sync now. */
@Composable
internal fun CloudSyncSection(
    onMessage: (String) -> Unit,
    vm: CloudSyncViewModel = hiltViewModel(),
) {
    val user by vm.user.collectAsStateWithLifecycle()
    val syncing by vm.syncing.collectAsStateWithLifecycle()
    val lastSyncedAt by vm.lastSyncedAt.collectAsStateWithLifecycle()

    LaunchedEffect(vm) { vm.messages.collect(onMessage) }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Panel,
        border = BorderStroke(1.dp, Edge),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(36.dp).background(SynapseDim, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painterResource(R.drawable.ic_cloud),
                    contentDescription = null,
                    tint = Synapse,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Links & topics backup", style = RowTitle, color = Ink)
                Text(
                    user?.email ?: "Not signed in",
                    style = MaterialTheme.typography.bodySmall,
                    color = Muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    if (syncing) "Syncing…" else lastSyncedLabel(lastSyncedAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = Muted,
                )
            }
            Spacer(Modifier.width(12.dp))
            TextButton(onClick = vm::syncNow, enabled = user != null && !syncing) {
                if (syncing) {
                    CircularProgressIndicator(Modifier.size(16.dp), color = Synapse, strokeWidth = 2.dp)
                } else {
                    Text("Sync now", color = Synapse, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

private fun lastSyncedLabel(millis: Long?): String {
    if (millis == null) return "Not synced yet"
    val now = System.currentTimeMillis()
    if (now - millis < DateUtils.MINUTE_IN_MILLIS) return "Last synced just now"
    return "Last synced " + DateUtils.getRelativeTimeSpanString(millis, now, DateUtils.MINUTE_IN_MILLIS)
}

private val RowTitle = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium)
