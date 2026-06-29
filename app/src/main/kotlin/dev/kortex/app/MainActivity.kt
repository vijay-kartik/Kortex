package dev.kortex.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.kortex.core.state.Message

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { ChatScreen() } }
    }
}

@Composable
fun ChatScreen(vm: ChatViewModel = viewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    var input by remember { mutableStateOf("") }

    ui.pendingApproval?.let { pending ->
        AlertDialog(
            onDismissRequest = { vm.resolveApproval(false) },
            title = { Text("Approve action?") },
            text = { Text(pending) },
            confirmButton = { TextButton(onClick = { vm.resolveApproval(true) }) { Text("Allow") } },
            dismissButton = { TextButton(onClick = { vm.resolveApproval(false) }) { Text("Deny") } },
        )
    }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Text("Kortex", style = MaterialTheme.typography.headlineSmall)

        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(ui.turns) { msg -> MessageBubble(msg) }
        }

        if (ui.trace.isNotEmpty()) {
            Text("trace", style = MaterialTheme.typography.labelSmall)
            Column(Modifier.heightIn(max = 100.dp).verticalScroll(rememberScrollState())) {
                ui.trace.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f).padding(8.dp),
            )
            Button(
                enabled = !ui.busy,
                onClick = { vm.send(input); input = "" },
            ) { Text(if (ui.busy) "..." else "Send") }
        }
    }
}

@Composable
private fun MessageBubble(msg: Message) {
    val isUser = msg.role == Message.Role.USER
    Surface(
        color = if (isUser) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium,
    ) {
        Text(msg.content, Modifier.padding(10.dp))
    }
}
