package dev.kortex.app.ui.screens.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.hilt.navigation.compose.hiltViewModel
import dev.kortex.design.R
import dev.kortex.app.data.local.ChatSessionEntity
import dev.kortex.design.Alarm
import dev.kortex.design.Muted
import dev.kortex.design.Panel
import dev.kortex.design.Synapse
import dev.kortex.design.Void
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HistoryScreen(
    onSelectSession: (String) -> Unit,
    onNewChat: () -> Unit,
    vm: ChatViewModel = hiltViewModel()
) {
    // Null while the first read is in flight: render nothing rather than the empty state.
    val sessions by vm.sessions.collectAsStateWithLifecycle()
    var pendingDelete by remember { mutableStateOf<ChatSessionEntity?>(null) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        floatingActionButton = {
            FloatingActionButton(
                onClick = onNewChat,
                containerColor = Synapse,
                contentColor = Void,
            ) {
                Icon(Icons.Default.Add, contentDescription = "New Chat")
            }
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (sessions?.isEmpty() == true) {
                item {
                    Text("No conversations yet — tap + to start one.", color = Muted)
                }
            }
            items(sessions.orEmpty(), key = { it.id }) { session ->
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .animateItem()
                        .clickable { onSelectSession(session.id) },
                    color = Panel,
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(session.title.ifEmpty { "New Conversation" }, style = MaterialTheme.typography.titleMedium)
                            Text(
                                SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault()).format(Date(session.updatedAtMillis)),
                                style = MaterialTheme.typography.bodySmall,
                                color = Muted,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                        IconButton(onClick = { pendingDelete = session }) {
                            Icon(
                                painterResource(R.drawable.ic_delete),
                                contentDescription = "Delete conversation",
                                tint = Muted,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }
        }
    }

    // ── Delete confirmation ─────────────────────────────────────────────
    pendingDelete?.let { session ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            containerColor = Panel,
            title = { Text("Delete conversation?") },
            text = {
                Text(
                    "\"${session.title.ifEmpty { "New Conversation" }}\" will be permanently deleted.",
                    color = Muted,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteSession(session.id)
                    pendingDelete = null
                }) {
                    Text("Delete", color = Alarm)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text("Cancel")
                }
            },
        )
    }
}
