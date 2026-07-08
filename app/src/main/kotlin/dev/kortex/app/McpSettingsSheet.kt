package dev.kortex.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.kortex.app.ui.Amber
import dev.kortex.app.ui.Alarm
import dev.kortex.app.ui.Edge
import dev.kortex.app.ui.Mono
import dev.kortex.app.ui.Muted
import dev.kortex.app.ui.Panel
import dev.kortex.app.ui.Synapse
import dev.kortex.app.ui.SynapseDim
import dev.kortex.app.ui.Void

// ── Status indicator colors ─────────────────────────────────────────────

private val StatusConnected = Color(0xFF4ADE80)
private val StatusConnecting = Amber
private val StatusError = Alarm

// ── Bottom sheet ────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun McpSettingsSheet(
    onDismiss: () -> Unit,
    vm: McpSettingsViewModel = viewModel(),
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
) {
    val ui by vm.ui.collectAsStateWithLifecycle()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Void,
        contentColor = MaterialTheme.colorScheme.onSurface,
        dragHandle = { SheetDragHandle() },
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // ── Header ──────────────────────────────────────────────
            item { SheetHeader() }

            // ── Built-in tools ──────────────────────────────────────
            item { SectionLabel("BUILT-IN TOOLS") }
            items(ui.builtinTools, key = { it.name }) { tool ->
                ToolRow(
                    name = tool.name,
                    description = tool.description,
                    enabled = tool.enabled,
                    onToggle = { vm.toggleTool(tool.name, it) },
                )
            }

            // ── MCP Servers ─────────────────────────────────────────
            val defaultServers = ui.servers.filter { it.isDefault }
            val customServers = ui.servers.filter { !it.isDefault }

            if (defaultServers.isNotEmpty()) {
                item { SectionLabel("DEFAULT SERVERS", Modifier.padding(top = 16.dp)) }
                defaultServers.forEach { server ->
                    item(key = "srv_${server.name}") {
                        ServerCard(
                            server = server,
                            onToggleTool = { name, enabled -> vm.toggleTool(name, enabled) },
                        )
                    }
                }
            }

            item {
                SectionLabel(
                    "CUSTOM SERVERS",
                    Modifier.padding(top = if (defaultServers.isNotEmpty()) 16.dp else 0.dp),
                )
            }

            if (customServers.isEmpty()) {
                item {
                    Text(
                        "No custom servers added yet.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Muted,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
            } else {
                customServers.forEach { server ->
                    item(key = "srv_${server.name}") {
                        ServerCard(
                            server = server,
                            onToggleTool = { name, enabled -> vm.toggleTool(name, enabled) },
                            onDelete = { vm.requestDelete(server.name) },
                        )
                    }
                }
            }

            // ── Add server button ───────────────────────────────────
            item {
                FilledTonalButton(
                    onClick = { vm.showAddDialog() },
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = SynapseDim,
                        contentColor = Synapse,
                    ),
                ) {
                    Text("+ Add MCP Server", fontWeight = FontWeight.Medium)
                }
            }
        }
    }

    // ── Add-server dialog ───────────────────────────────────────────────
    if (ui.showAddDialog) {
        AddServerDialog(
            onDismiss = { vm.dismissAddDialog() },
            onConfirm = { name, url, token -> vm.addServer(name, url, token) },
        )
    }

    // ── Delete confirmation ─────────────────────────────────────────────
    ui.pendingDelete?.let { serverName ->
        AlertDialog(
            onDismissRequest = { vm.cancelDelete() },
            containerColor = Panel,
            title = { Text("Remove server?") },
            text = {
                Text(
                    "\"$serverName\" and all its tools will be removed.",
                    color = Muted,
                )
            },
            confirmButton = {
                TextButton(onClick = { vm.confirmDelete(serverName) }) {
                    Text("Remove", color = Alarm)
                }
            },
            dismissButton = {
                TextButton(onClick = { vm.cancelDelete() }) {
                    Text("Cancel")
                }
            },
        )
    }
}

// ── Composable pieces ───────────────────────────────────────────────────

@Composable
private fun SheetDragHandle() {
    Box(Modifier.fillMaxWidth().padding(top = 10.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .width(36.dp)
                .height(4.dp)
                .background(Edge, RoundedCornerShape(2.dp)),
        )
    }
}

@Composable
private fun SheetHeader() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp, top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Tools & Servers",
            style = MaterialTheme.typography.titleLarge,
        )
        Spacer(Modifier.weight(1f))
        Text(
            "MCP",
            style = MaterialTheme.typography.labelSmall,
            color = Synapse,
        )
    }
}

@Composable
private fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.2.sp),
        color = Muted,
        modifier = modifier.padding(vertical = 8.dp),
    )
}

/** A single tool row with a toggle switch. */
@Composable
private fun ToolRow(
    name: String,
    description: String,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Panel,
        border = BorderStroke(1.dp, Edge),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    name,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontFamily = Mono,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                    color = if (enabled) MaterialTheme.colorScheme.onSurface else Muted,
                )
                if (description.isNotBlank()) {
                    Text(
                        description,
                        style = MaterialTheme.typography.bodySmall,
                        color = Muted,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Switch(
                checked = enabled,
                onCheckedChange = onToggle,
                colors = SwitchDefaults.colors(
                    checkedTrackColor = Synapse,
                    checkedThumbColor = Void,
                    uncheckedTrackColor = Edge,
                    uncheckedThumbColor = Muted,
                    uncheckedBorderColor = Color.Transparent,
                ),
            )
        }
    }
}

/** An expandable server card that shows status + its discovered tools. */
@Composable
private fun ServerCard(
    server: ServerEntry,
    onToggleTool: (String, Boolean) -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    var expanded by remember { mutableStateOf(false) }

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = Panel,
        border = BorderStroke(1.dp, Edge),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            // ── Server header ───────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Status dot
                Box(
                    Modifier
                        .size(8.dp)
                        .background(
                            when (server.status) {
                                ServerStatus.CONNECTED -> StatusConnected
                                ServerStatus.CONNECTING -> StatusConnecting
                                ServerStatus.ERROR -> StatusError
                            },
                            CircleShape,
                        ),
                )
                Spacer(Modifier.width(10.dp))

                Column(Modifier.weight(1f)) {
                    Text(
                        server.name,
                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                    )
                    Text(
                        server.url,
                        style = MaterialTheme.typography.labelSmall,
                        color = Muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                // Tool count badge
                Text(
                    "${server.tools.size} tool${if (server.tools.size == 1) "" else "s"}",
                    style = MaterialTheme.typography.labelSmall,
                    color = Muted,
                )

                if (onDelete != null) {
                    Spacer(Modifier.width(4.dp))
                    IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                        Icon(
                            painterResource(R.drawable.ic_delete),
                            contentDescription = "Remove server",
                            tint = Muted,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }

                Spacer(Modifier.width(4.dp))
                Text(
                    if (expanded) "▲" else "▼",
                    style = MaterialTheme.typography.labelSmall,
                    color = Muted,
                )
            }

            // ── Expanded tool list ──────────────────────────────────
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(),
                exit = shrinkVertically(),
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(Void.copy(alpha = 0.5f))
                        .padding(start = 14.dp, end = 14.dp, bottom = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (server.tools.isEmpty()) {
                        val msg = when (server.status) {
                            ServerStatus.CONNECTING -> "Connecting…"
                            ServerStatus.ERROR -> "Failed to connect."
                            ServerStatus.CONNECTED -> "No tools discovered."
                        }
                        Text(
                            msg,
                            style = MaterialTheme.typography.bodySmall,
                            color = Muted,
                            modifier = Modifier.padding(vertical = 6.dp),
                        )
                    } else {
                        server.tools.forEach { tool ->
                            McpToolRow(
                                name = tool.name,
                                description = tool.description,
                                enabled = tool.enabled,
                                onToggle = { onToggleTool(tool.name, it) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Compact tool row used inside expanded server cards. */
@Composable
private fun McpToolRow(
    name: String,
    description: String,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                name,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = Mono,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                ),
                color = if (enabled) MaterialTheme.colorScheme.onSurface else Muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (description.isNotBlank()) {
                Text(
                    description,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                    color = Muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Switch(
            checked = enabled,
            onCheckedChange = onToggle,
            modifier = Modifier.height(28.dp),
            colors = SwitchDefaults.colors(
                checkedTrackColor = Synapse,
                checkedThumbColor = Void,
                uncheckedTrackColor = Edge,
                uncheckedThumbColor = Muted,
                uncheckedBorderColor = Color.Transparent,
            ),
        )
    }
}

// ── Add Server Dialog ───────────────────────────────────────────────────

@Composable
private fun AddServerDialog(
    onDismiss: () -> Unit,
    onConfirm: (name: String, url: String, token: String?) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var showToken by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Panel,
        title = { Text("Add MCP Server") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SettingsTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = "Server name",
                    placeholder = "e.g. linear",
                )
                SettingsTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = "Endpoint URL",
                    placeholder = "https://mcp.example.com/mcp",
                    keyboardType = KeyboardType.Uri,
                )
                SettingsTextField(
                    value = token,
                    onValueChange = { token = it },
                    label = "Bearer token (optional)",
                    placeholder = "sk-…",
                    isPassword = !showToken,
                    trailingContent = {
                        TextButton(onClick = { showToken = !showToken }) {
                            Text(
                                if (showToken) "hide" else "show",
                                style = MaterialTheme.typography.labelSmall,
                                color = Synapse,
                            )
                        }
                    },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name, url, token.ifBlank { null }) },
                enabled = name.isNotBlank() && url.isNotBlank(),
            ) {
                Text("Add", color = if (name.isNotBlank() && url.isNotBlank()) Synapse else Muted)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun SettingsTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String,
    keyboardType: KeyboardType = KeyboardType.Text,
    isPassword: Boolean = false,
    trailingContent: @Composable (() -> Unit)? = null,
) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = Muted)
        Spacer(Modifier.height(4.dp))
        TextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = { Text(placeholder, color = Muted.copy(alpha = 0.5f)) },
            singleLine = true,
            visualTransformation = if (isPassword) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = Mono),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Void,
                unfocusedContainerColor = Void,
                cursorColor = Synapse,
                focusedIndicatorColor = Synapse,
                unfocusedIndicatorColor = Edge,
            ),
            trailingIcon = trailingContent,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
