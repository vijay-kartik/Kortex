package dev.kortex.app

import android.accounts.AccountManager
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
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

import androidx.compose.material3.Scaffold
import androidx.compose.material3.SheetState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.kortex.app.auth.GmailAuthManager
import dev.kortex.app.ui.Amber
import kotlinx.coroutines.launch
import android.widget.Toast
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

// ── Screen ────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onDismiss: () -> Unit,
    vm: SettingsViewModel = viewModel(),
) {
    val ui by vm.ui.collectAsStateWithLifecycle()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            androidx.compose.material3.TopAppBar(
                title = { Text("Tools & Settings") },
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Text("←", fontSize = 24.sp, color = Muted)
                    }
                },
                colors = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                )
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {


            // ── LLM Provider ───────────────────────────────────────────
            item { SectionLabel("LLM PROVIDER") }
            item {
                ModelSelector(
                    activeProvider = ui.activeProvider,
                    activeModel = ui.activeModel,
                    supportedModels = ui.supportedModels,
                    ollamaUrl = ui.ollamaUrl,
                    ollamaToken = ui.ollamaToken,
                    openaiApiKey = ui.openaiApiKey,
                    ollamaCloudApiKey = ui.ollamaCloudApiKey,
                    onProviderSelected = { vm.setActiveProvider(it) },
                    onModelSelected = { vm.setActiveModel(it) },
                    onOllamaUrlChange = { vm.setOllamaUrl(it) },
                    onOllamaTokenChange = { vm.setOllamaToken(it) },
                    onOpenaiApiKeyChange = { vm.setOpenaiApiKey(it) },
                    onOllamaCloudApiKeyChange = { vm.setOllamaCloudApiKey(it) },
                )
            }

            // ── Embedding Provider ───────────────────────────────────────────
            item { SectionLabel("EMBEDDING PROVIDER", Modifier.padding(top = 16.dp)) }
            item {
                EmbeddingInfo(
                    testResult = ui.testEmbeddingResult,
                    onTestConnection = { vm.testEmbeddingConnection() },
                    onClearTest = { vm.clearTestEmbeddingResult() }
                )
            }



            // ── Native Gmail ─────────────────────────────────────────
            item { SectionLabel("NATIVE GMAIL (REST API)", Modifier.padding(top = 16.dp)) }
            item {
                NativeGmailSettings(
                    email = ui.gmailAccountEmail,
                    onConnect = { vm.setGmailAccountEmail(it) },
                    onDisconnect = { vm.setGmailAccountEmail(null) }
                )
            }

            // ── Built-in tools ──────────────────────────────────────
            item { SectionLabel("BUILT-IN TOOLS", Modifier.padding(top = 16.dp)) }
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
                            onToggleServer = { enabled -> vm.toggleServer(server.name, enabled) },
                            onSignIn = { vm.signIn(server.name) },
                            onSignOut = { vm.signOut(server.name) },
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
                            onToggleServer = { enabled -> vm.toggleServer(server.name, enabled) },
                            onDelete = { vm.requestDelete(server.name) },
                            onSignIn = { vm.signIn(server.name) },
                            onSignOut = { vm.signOut(server.name) },
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
    onToggleServer: (Boolean) -> Unit,
    onDelete: (() -> Unit)? = null,
    onSignIn: (() -> Unit)? = null,
    onSignOut: (() -> Unit)? = null,
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
                                ServerStatus.NEEDS_AUTH -> Amber
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

                if (server.tools.isNotEmpty()) {
                    Spacer(Modifier.width(12.dp))
                    Switch(
                        checked = server.tools.any { it.enabled },
                        onCheckedChange = onToggleServer,
                        colors = SwitchDefaults.colors(
                            checkedTrackColor = Synapse,
                            checkedThumbColor = Void,
                            uncheckedTrackColor = Edge,
                            uncheckedThumbColor = Muted,
                            uncheckedBorderColor = Color.Transparent,
                        ),
                        modifier = Modifier.height(28.dp)
                    )
                }

                if (server.hasOAuthSession) {
                    Spacer(Modifier.width(8.dp))
                    TextButton(
                        onClick = { onSignOut?.invoke() },
                        modifier = Modifier.height(24.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp)
                    ) {
                        Text("Sign out", fontSize = 12.sp, color = Muted)
                    }
                } else if (server.status == ServerStatus.ERROR || server.status == ServerStatus.NEEDS_AUTH) {
                    Spacer(Modifier.width(8.dp))
                    TextButton(
                        onClick = { onSignIn?.invoke() },
                        modifier = Modifier.height(24.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp)
                    ) {
                        Text("Sign In", fontSize = 12.sp, color = Amber)
                    }
                }

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
                        if (server.status == ServerStatus.NEEDS_AUTH) {
                            var pending by remember(server.status) { mutableStateOf(false) }
                            Text(
                                if (pending) "Waiting for browser sign-in…" else "Sign in required",
                                style = MaterialTheme.typography.bodySmall,
                                color = Amber,
                                modifier = Modifier.padding(vertical = 6.dp),
                            )
                            Spacer(Modifier.height(4.dp))
                            FilledTonalButton(
                                onClick = { 
                                    pending = true
                                    onSignIn?.invoke()
                                },
                                colors = ButtonDefaults.filledTonalButtonColors(
                                    containerColor = SynapseDim,
                                    contentColor = Synapse
                                )
                            ) {
                                Text("Sign in")
                            }
                        } else {
                            val msg = when (server.status) {
                                ServerStatus.CONNECTING -> "Connecting…"
                                ServerStatus.ERROR -> "Failed to connect."
                                ServerStatus.CONNECTED -> "No tools discovered."
                                else -> "" // fallback, though unused for NEEDS_AUTH now
                            }
                            if (msg.isNotEmpty()) {
                                Text(
                                    msg,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (server.status == ServerStatus.ERROR) Alarm else Muted,
                                    modifier = Modifier.padding(vertical = 6.dp),
                                )
                            }
                        }
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
                    label = "API key (optional — OAuth servers can be signed into after adding)",
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



@Composable
private fun ModelSelector(
    activeProvider: String,
    activeModel: String,
    supportedModels: List<String>,
    ollamaUrl: String,
    ollamaToken: String,
    openaiApiKey: String,
    ollamaCloudApiKey: String,
    onProviderSelected: (String) -> Unit,
    onModelSelected: (String) -> Unit,
    onOllamaUrlChange: (String) -> Unit,
    onOllamaTokenChange: (String) -> Unit,
    onOpenaiApiKeyChange: (String) -> Unit,
    onOllamaCloudApiKeyChange: (String) -> Unit,
) {
    var expandedProvider by remember { mutableStateOf(false) }
    var expandedModel by remember { mutableStateOf(false) }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Panel,
        border = BorderStroke(1.dp, Edge),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            // Provider Selection
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expandedProvider = !expandedProvider }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "Provider",
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    )
                    Text(
                        when (activeProvider) {
                            "openai" -> "OpenAI"
                            "ollama-cloud" -> "Ollama Cloud"
                            else -> "Ollama (Local)"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = Synapse,
                    )
                }
                Text(if (expandedProvider) "▲" else "▼", style = MaterialTheme.typography.labelSmall, color = Muted)
            }
            AnimatedVisibility(visible = expandedProvider) {
                Column(modifier = Modifier.fillMaxWidth().background(Void.copy(alpha = 0.5f)).padding(bottom = 8.dp)) {
                    listOf(
                        "openai" to "OpenAI",
                        "ollama" to "Ollama (Local)",
                        "ollama-cloud" to "Ollama Cloud",
                    ).forEach { (id, label) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onProviderSelected(id)
                                    expandedProvider = false
                                }
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                label,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (id == activeProvider) Synapse else Muted,
                                fontWeight = if (id == activeProvider) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                }
            }

            // Model Selection
            if (activeProvider == "openai" || activeProvider == "ollama" || activeProvider == "ollama-cloud") {
                Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expandedModel = !expandedModel }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "Active Model",
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    )
                    Text(
                        activeModel,
                        style = MaterialTheme.typography.bodySmall,
                        color = Synapse,
                    )
                }
                Text(if (expandedModel) "▲" else "▼", style = MaterialTheme.typography.labelSmall, color = Muted)
            }
            AnimatedVisibility(visible = expandedModel) {
                Column(modifier = Modifier.fillMaxWidth().background(Void.copy(alpha = 0.5f)).padding(bottom = 8.dp)) {
                    // OpenAI and Ollama Cloud get a curated tap-to-pick list; the cloud
                    // catalog changes often, so Ollama Cloud also keeps the free-text field.
                    val quickPickModels = when (activeProvider) {
                        "openai" -> supportedModels
                        "ollama-cloud" -> dev.kortex.core.llm.Models.supportedOllamaCloud
                        else -> emptyList()
                    }
                    quickPickModels.forEach { model ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onModelSelected(model)
                                    expandedModel = false
                                }
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                model,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (model == activeModel) Synapse else Muted,
                                fontWeight = if (model == activeModel) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                    if (activeProvider != "openai") {
                        // For Ollama (local or cloud), users can type any model name
                        var customModel by remember { mutableStateOf(activeModel) }
                        Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
                            SettingsTextField(
                                value = customModel,
                                onValueChange = { customModel = it },
                                label = "Model Name",
                                placeholder = if (activeProvider == "ollama-cloud") "qwen3.5:122b" else "llama3:latest"
                            )
                            Spacer(Modifier.height(8.dp))
                            ButtonDefaults.filledTonalButtonColors()
                            FilledTonalButton(
                                onClick = { 
                                    onModelSelected(customModel)
                                    expandedModel = false
                                },
                                modifier = Modifier.align(Alignment.End),
                                colors = ButtonDefaults.filledTonalButtonColors(containerColor = SynapseDim, contentColor = Synapse)
                            ) {
                                Text("Save Model")
                            }
                        }
                    }
                }
                }
            }

            // OpenAI settings (Only if OpenAI)
            if (activeProvider == "openai") {
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)) {
                    var editKey by remember { mutableStateOf(openaiApiKey) }
                    var showKey by remember { mutableStateOf(false) }
                    SettingsTextField(
                        value = editKey,
                        onValueChange = { editKey = it; onOpenaiApiKeyChange(it) },
                        label = "OpenAI API Key",
                        placeholder = "sk-...",
                        isPassword = !showKey,
                        trailingContent = {
                            TextButton(onClick = { showKey = !showKey }) {
                                Text(
                                    if (showKey) "hide" else "show",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Synapse,
                                )
                            }
                        },
                    )
                    val buildKeyPresent = BuildConfig.OPENAI_API_KEY.isNotBlank()
                    Text(
                        when {
                            editKey.isNotBlank() -> "Stored on this device; overrides any key bundled at build time."
                            buildKeyPresent -> "Currently using the key from local.properties. A key entered here overrides it."
                            else -> "No key set — OpenAI requests won't work until you add one. Create a key at platform.openai.com."
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (editKey.isBlank() && !buildKeyPresent) Amber else Muted,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }

            // Ollama settings (Only if Ollama)
            if (activeProvider == "ollama") {
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)) {
                    var editUrl by remember { mutableStateOf(ollamaUrl) }
                    SettingsTextField(
                        value = editUrl,
                        onValueChange = { editUrl = it; onOllamaUrlChange(it) },
                        label = "Ollama Base URL",
                        placeholder = "http://10.0.2.2:11434/v1",
                        keyboardType = KeyboardType.Uri
                    )
                    Spacer(Modifier.height(12.dp))
                    var editToken by remember { mutableStateOf(ollamaToken) }
                    var showToken by remember { mutableStateOf(false) }
                    SettingsTextField(
                        value = editToken,
                        onValueChange = { editToken = it; onOllamaTokenChange(it) },
                        label = "API Key (optional, for OpenAI-compatible hosts)",
                        placeholder = "sk-...",
                        isPassword = !showToken,
                        trailingContent = {
                            TextButton(onClick = { showToken = !showToken }) {
                                Text(
                                    if (showToken) "hide" else "show",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Synapse,
                                )
                            }
                        }
                    )
                }
            }

            // Ollama Cloud settings (Only if Ollama Cloud)
            if (activeProvider == "ollama-cloud") {
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)) {
                    var editKey by remember { mutableStateOf(ollamaCloudApiKey) }
                    var showKey by remember { mutableStateOf(false) }
                    SettingsTextField(
                        value = editKey,
                        onValueChange = { editKey = it; onOllamaCloudApiKeyChange(it) },
                        label = "Ollama Cloud API Key",
                        placeholder = "from ollama.com/settings/keys",
                        isPassword = !showKey,
                        trailingContent = {
                            TextButton(onClick = { showKey = !showKey }) {
                                Text(
                                    if (showKey) "hide" else "show",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Synapse,
                                )
                            }
                        },
                    )
                    Text(
                        if (editKey.isBlank()) {
                            "No key set — create one at ollama.com/settings/keys. Until then, requests fall back to the default provider."
                        } else {
                            "Stored on this device. Vision-capable cloud models (qwen3.5, gemma4, kimi-k2.7) can read image attachments."
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (editKey.isBlank()) Amber else Muted,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun EmbeddingInfo(
    testResult: String?,
    onTestConnection: () -> Unit,
    onClearTest: () -> Unit,
) {
    // MANAGE_EXTERNAL_STORAGE ("All files access") is required to read the model +
    // tokenizer from public Downloads. It can't be granted via the runtime dialog —
    // only through a system Settings screen — so we track the current grant state and
    // re-check when the user returns from that screen.
    fun hasAllFilesAccess(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()

    val context = LocalContext.current
    var storageGranted by remember { mutableStateOf(hasAllFilesAccess()) }
    val storageLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        // The Settings screen returns no result code for this grant; re-read the live state.
        storageGranted = hasAllFilesAccess()
    }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Panel,
        border = BorderStroke(1.dp, Edge),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            // Fixed on-device provider — no configuration; EmbeddingGemma is the
            // single embedding engine (see KortexContainer.embedder).
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "Provider",
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    )
                    Text(
                        "On-device — EmbeddingGemma (384-dim)",
                        style = MaterialTheme.typography.bodySmall,
                        color = Synapse,
                    )
                }
            }

            // Storage-access gate: the model lives in Downloads, unreadable without
            // all-files access. Show status + a grant shortcut when it's missing.
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)) {
                Text(
                    "Model file storage access",
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                    )
                )
                Text(
                    if (storageGranted) "Granted — model can be read from Downloads."
                    else "Not granted — embeddings can't load the model until you allow all-files access.",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (storageGranted) StatusConnected else Amber,
                    modifier = Modifier.padding(top = 2.dp),
                )
                if (!storageGranted) {
                    Spacer(Modifier.height(8.dp))
                    FilledTonalButton(
                        onClick = {
                            val intent = Intent(
                                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                Uri.parse("package:${context.packageName}")
                            )
                            storageLauncher.launch(intent)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.filledTonalButtonColors(containerColor = SynapseDim, contentColor = Synapse)
                    ) {
                        Text("Grant storage access")
                    }
                }
            }

            // Self-test: confirms the on-device model loads and returns a vector.
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)) {
                FilledTonalButton(
                    onClick = {
                        onClearTest()
                        onTestConnection()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.filledTonalButtonColors(containerColor = SynapseDim, contentColor = Synapse)
                ) {
                    Text("Test Embedding")
                }
                if (testResult != null) {
                    Text(
                        text = testResult,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (testResult.startsWith("Failed")) Alarm else StatusConnected,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        }
    }
}

// ── Native Gmail UI ──────────────────────────────────────────────────────

@Composable
fun NativeGmailSettings(
    email: String?,
    onConnect: (String) -> Unit,
    onDisconnect: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val authManager = remember { GmailAuthManager(context) }
    var pendingEmail by remember { mutableStateOf<String?>(null) }

    val consentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            pendingEmail?.let { acc ->
                scope.launch {
                    when (authManager.getToken(acc)) {
                        is GmailAuthManager.AuthResult.Success -> onConnect(acc)
                        else -> Toast.makeText(context, "Failed to get token after consent.", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val accountName = result.data?.getStringExtra(AccountManager.KEY_ACCOUNT_NAME)
            if (!accountName.isNullOrBlank()) {
                pendingEmail = accountName
                scope.launch {
                    when (val authRes = authManager.getToken(accountName)) {
                        is GmailAuthManager.AuthResult.Success -> onConnect(accountName)
                        is GmailAuthManager.AuthResult.NeedsConsent -> consentLauncher.launch(authRes.intent)
                        is GmailAuthManager.AuthResult.Error -> Toast.makeText(context, "Error: ${authRes.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Panel,
        border = BorderStroke(1.dp, Edge),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                // Icon placeholder or specific Gmail icon
                Surface(
                    shape = CircleShape,
                    color = SynapseDim,
                    modifier = Modifier.size(40.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text("G", color = Synapse, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Google Account",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        if (email.isNullOrBlank()) "Not connected" else email,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (email.isNullOrBlank()) Muted else StatusConnected,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Text(
                "Allows Kortex to search and read emails using the native Gmail REST API without relying on third-party services. Required for the gmail_search tool.",
                style = MaterialTheme.typography.bodySmall,
                color = Muted,
                lineHeight = 18.sp
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                if (!email.isNullOrBlank()) {
                    TextButton(
                        onClick = onDisconnect,
                        colors = ButtonDefaults.textButtonColors(contentColor = Alarm)
                    ) {
                        Text("Disconnect")
                    }
                } else {
                    FilledTonalButton(
                        onClick = {
                            val intent = GmailAuthManager(context).pickGoogleAccountIntent()
                            launcher.launch(intent)
                        },
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = SynapseDim,
                            contentColor = Synapse
                        )
                    ) {
                        Text("Connect Account")
                    }
                }
            }
        }
    }
}
