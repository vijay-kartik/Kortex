package dev.kortex.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.kortex.core.ambient.MemoryEntry

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContextScreen(modifier: Modifier = Modifier, vm: ContextViewModel = viewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    var expanded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { vm.refresh() }

    Column(modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = !expanded },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
        ) {
            val selectedContact = ui.contacts.find { it.id == ui.selectedContactId }
            OutlinedTextField(
                value = selectedContact?.displayName ?: "Select a contact",
                onValueChange = {},
                readOnly = true,
                label = { Text("View context for") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
                modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth()
            )

            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                ui.contacts.forEach { contact ->
                    DropdownMenuItem(
                        text = { Text(contact.displayName) },
                        onClick = {
                            vm.onContactSelected(contact.id)
                            expanded = false
                        }
                    )
                }
            }
        }

        LazyColumn(
            modifier = Modifier.weight(1f).padding(top = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                SectionHeader("Conversation Summary")
                Card(Modifier.fillMaxWidth()) {
                    Text(
                        ui.conversation?.summary ?: "No summary yet. Ingest some signals first.",
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            item {
                SectionHeader("Durable Memories (Layer 3)")
                if (ui.memories.isEmpty()) {
                    Text("No memories extracted yet.", style = MaterialTheme.typography.bodySmall)
                }
            }

            items(ui.memories) { memory ->
                MemoryItem(memory)
            }

            if (ui.entities.isNotEmpty()) {
                item {
                    SectionHeader("Extracted Entities")
                    EntityCloud(ui.entities.map { it.name })
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Column(Modifier.padding(bottom = 8.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        HorizontalDivider(Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun MemoryItem(memory: MemoryEntry) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(memory.kind.name, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                Text("Salience: ${"%.2f".format(memory.salience)}", style = MaterialTheme.typography.labelSmall)
            }
            Text(memory.content, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
            if (memory.tags.isNotEmpty()) {
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    memory.tags.forEach { tag ->
                        Surface(
                            shape = MaterialTheme.shapes.extraSmall,
                            color = MaterialTheme.colorScheme.secondaryContainer
                        ) {
                            Text(
                                tag,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EntityCloud(names: List<String>) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        names.forEach { name ->
            AssistChip(onClick = {}, label = { Text(name) })
        }
    }
}
