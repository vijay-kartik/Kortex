package dev.kortex.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HistoryScreen(
    onSelectSession: (String) -> Unit,
    vm: ChatViewModel = viewModel()
) {
    val sessions by vm.sessions.collectAsStateWithLifecycle(emptyList())

    androidx.compose.material3.Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        floatingActionButton = {
            androidx.compose.material3.FloatingActionButton(
                onClick = {
                    vm.startNewSession()
                    onSelectSession("") // Triggers tab change
                },
                containerColor = dev.kortex.app.ui.Synapse,
                contentColor = dev.kortex.app.ui.Void
            ) {
                androidx.compose.material3.Icon(
                    Icons.Default.Add,
                    contentDescription = "New Chat"
                )
            }
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (sessions.isEmpty()) {
                item {
                    Text("No past conversations", color = dev.kortex.app.ui.Muted)
                }
            }
            items(sessions) { session ->
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelectSession(session.id) },
                    color = dev.kortex.app.ui.Panel,
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(session.title.ifEmpty { "New Conversation" }, style = MaterialTheme.typography.titleMedium)
                        Text(
                            SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault()).format(Date(session.updatedAtMillis)),
                            style = MaterialTheme.typography.bodySmall,
                            color = dev.kortex.app.ui.Muted,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }
        }
    }
}
