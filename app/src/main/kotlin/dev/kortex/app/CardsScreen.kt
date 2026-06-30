package dev.kortex.app

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.kortex.core.ambient.ActionCard
import dev.kortex.core.ambient.CardAction
import dev.kortex.core.ambient.requiresApproval
import dev.kortex.core.ambient.risk
import dev.kortex.core.tool.RiskLevel

@Composable
fun CardsScreen(modifier: Modifier = Modifier, vm: CardsViewModel = viewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    var pending by remember { mutableStateOf<Pair<ActionCard, CardAction>?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) vm.syncContacts() }

    LaunchedEffect(Unit) { vm.refresh() }

    pending?.let { (card, action) ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text("Confirm action") },
            text = { Text("\"${action.label}\" will act on your behalf. Continue?") },
            confirmButton = {
                TextButton(onClick = { vm.act(card, action); pending = null }) { Text("Confirm") }
            },
            dismissButton = { TextButton(onClick = { pending = null }) { Text("Cancel") } },
        )
    }

    Column(modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        OutlinedTextField(
            value = ui.testSignalText,
            onValueChange = { vm.onTestSignalTextChanged(it) },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            label = { Text("Test Signal Content") },
            enabled = !ui.busy,
            maxLines = 3
        )

        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                enabled = !ui.busy,
                onClick = { permissionLauncher.launch(Manifest.permission.READ_CONTACTS) },
            ) { Text("Sync contacts") }
            OutlinedButton(enabled = !ui.busy, onClick = { vm.injectTestSignal() }) { Text("Test signal") }
        }

        ui.status?.let { Text(it, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(vertical = 2.dp)) }
        if (ui.busy) LinearProgressIndicator(Modifier.fillMaxWidth())

        if (ui.cards.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "No cards yet.\nSync contacts, then tap \"Test signal\".",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(ui.cards) { card ->
                    CardItem(
                        card = card,
                        onAction = { action ->
                            if (action.risk() != RiskLevel.LOW) pending = card to action
                            else vm.act(card, action)
                        },
                        onDismiss = { vm.dismiss(card) },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CardItem(card: ActionCard, onAction: (CardAction) -> Unit, onDismiss: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(card.title, style = MaterialTheme.typography.titleMedium)
                Text(card.priority.name, style = MaterialTheme.typography.labelSmall)
            }
            Text(
                card.summary,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 6.dp),
            )
            if (card.requiresApproval()) {
                Text(
                    "Some actions need confirmation",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            FlowRow(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                card.actions.forEach { action ->
                    OutlinedButton(onClick = { onAction(action) }) { Text(action.label) }
                }
                TextButton(onClick = onDismiss) { Text("Dismiss") }
            }
        }
    }
}
