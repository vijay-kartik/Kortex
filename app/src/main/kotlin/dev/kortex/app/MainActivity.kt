package dev.kortex.app

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.kortex.core.state.Message

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { MaterialTheme { RootScreen() } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RootScreen() {
    var tab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Cards", "Chat", "Context")

    Scaffold(
        topBar = {
            Column {
                TopAppBar(title = { Text("Kortex") })
                TabRow(selectedTabIndex = tab) {
                    tabs.forEachIndexed { i, title ->
                        Tab(selected = tab == i, onClick = { tab = i }, text = { Text(title) })
                    }
                }
            }
        },
    ) { innerPadding ->
        Box(Modifier.padding(innerPadding)) {
            when (tab) {
                0 -> CardsScreen()
                1 -> ChatScreen()
                else -> ContextScreen()
            }
        }
    }
}

@Composable
fun ChatScreen(modifier: Modifier = Modifier, vm: ChatViewModel = viewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(ui.turns.size) {
        if (ui.turns.isNotEmpty()) listState.animateScrollToItem(ui.turns.lastIndex)
    }

    ui.pendingApproval?.let { pending ->
        AlertDialog(
            onDismissRequest = { vm.resolveApproval(false) },
            title = { Text("Approve action?") },
            text = { Text(pending) },
            confirmButton = { TextButton(onClick = { vm.resolveApproval(true) }) { Text("Allow") } },
            dismissButton = { TextButton(onClick = { vm.resolveApproval(false) }) { Text("Deny") } },
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .imePadding()
            .padding(horizontal = 12.dp),
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 8.dp),
        ) {
            items(ui.turns) { msg -> MessageBubble(msg) }
        }

        if (ui.trace.isNotEmpty()) {
            Text("trace", style = MaterialTheme.typography.labelSmall)
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 100.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 8.dp),
            ) {
                ui.trace.forEach { line ->
                    Text(line, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }

        if (ui.busy) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Text(ui.status ?: "Working…", style = MaterialTheme.typography.bodySmall)
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Ask Kortex anything…") },
                enabled = !ui.busy,
                maxLines = 4,
            )
            Button(
                enabled = !ui.busy && input.isNotBlank(),
                onClick = { vm.send(input); input = "" },
            ) { Text(if (ui.busy) "…" else "Send") }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(msg: Message) {
    val isUser = msg.role == Message.Role.USER
    val clipboard = LocalClipboardManager.current
    val haptics = LocalHapticFeedback.current
    val context = LocalContext.current

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            color = if (isUser) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier
                .widthIn(max = 320.dp)
                .combinedClickable(
                    onClick = {},
                    onLongClick = {
                        clipboard.setText(AnnotatedString(msg.content))
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                    },
                ),
        ) {
            Text(msg.content, Modifier.padding(12.dp))
        }
    }
}
