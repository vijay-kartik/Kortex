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

// ── Screen ────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun McpSettingsScreen(
    onDismiss: () -> Unit,
    vm: McpSettingsViewModel = viewModel(),
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
                    mediaPipeModelPath = ui.mediaPipeModelPath,
                    onProviderSelected = { vm.setActiveProvider(it) },
                    onModelSelected = { vm.setActiveModel(it) },
                    onOllamaUrlChange = { vm.setOllamaUrl(it) },
                    onOllamaTokenChange = { vm.setOllamaToken(it) },
                    onMediaPipeModelPathChange = { vm.setMediaPipeModelPath(it) },
                    onDownloadGguf = { ctx, url, name -> vm.downloadGgufModel(ctx, url, name) }
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

@Composable
private fun ModelSelector(
    activeProvider: String,
    activeModel: String,
    supportedModels: List<String>,
    ollamaUrl: String,
    ollamaToken: String,
    mediaPipeModelPath: String,
    onProviderSelected: (String) -> Unit,
    onModelSelected: (String) -> Unit,
    onOllamaUrlChange: (String) -> Unit,
    onOllamaTokenChange: (String) -> Unit,
    onMediaPipeModelPathChange: (String) -> Unit,
    onDownloadGguf: (android.content.Context, String, String) -> Unit
) {
    var expandedProvider by remember { mutableStateOf(false) }
    var expandedModel by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current

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
                            "mediapipe" -> "MediaPipe (On-Device GPU/CPU)"
                            "llamacpp" -> "Llama.cpp (.gguf via NDK)"
                            else -> "Ollama (Local/Cloud)"
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
                        "ollama" to "Ollama (Local/Cloud)", 
                        "mediapipe" to "MediaPipe (On-Device GPU/CPU)",
                        "llamacpp" to "Llama.cpp (.gguf via NDK)"
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
            if (activeProvider == "openai" || activeProvider == "ollama") {
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
                    if (activeProvider == "openai") {
                        supportedModels.forEach { model ->
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
                    } else {
                        // For Ollama, users type the model name
                        var customModel by remember { mutableStateOf(activeModel) }
                        Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
                            SettingsTextField(
                                value = customModel,
                                onValueChange = { customModel = it },
                                label = "Model Name",
                                placeholder = "llama3:latest"
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
                        label = "API Key (optional, for Cloud/Groq/Together)",
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

            // MediaPipe & Llama.cpp Settings
            if (activeProvider == "mediapipe" || activeProvider == "llamacpp") {
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)) {
                    var editPath by remember { mutableStateOf(mediaPipeModelPath) }
                    Text(
                        if (activeProvider == "mediapipe")
                            "MediaPipe supports Gemma, Phi-2, Falcon, and StableLM. You can download the official Gemma 2B model below, or use the MediaPipe Python conversion script to convert other models and enter the absolute path to the .bin file."
                        else
                            "Llama.cpp supports any .gguf file (Llama 3, Mistral, Qwen, etc). Download a .gguf file from HuggingFace to your device and enter its absolute path here.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Muted,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                    SettingsTextField(
                        value = editPath,
                        onValueChange = { editPath = it; onMediaPipeModelPathChange(it) },
                        label = if (activeProvider == "mediapipe") "Absolute Path to .bin Model File" else "Absolute Path to .gguf Model File",
                        placeholder = if (activeProvider == "mediapipe") "/storage/emulated/0/Download/gemma-2b-it-gpu-int4.bin" else "/storage/emulated/0/Download/llama-3-8b.gguf"
                    )
                    Spacer(Modifier.height(12.dp))
                    if (activeProvider == "mediapipe") {
                        ButtonDefaults.filledTonalButtonColors()
                        FilledTonalButton(
                            onClick = { 
                                val intent = android.content.Intent(
                                    android.content.Intent.ACTION_VIEW, 
                                    android.net.Uri.parse("https://www.kaggle.com/models/google/gemma/tfLite/gemma-2b-it-gpu-int4")
                                )
                                context.startActivity(intent)
                            },
                            modifier = Modifier.align(Alignment.End),
                            colors = ButtonDefaults.filledTonalButtonColors(containerColor = SynapseDim, contentColor = Synapse)
                        ) {
                            Text("Open Kaggle to Download Gemma 2B")
                        }
                    }

                    if (activeProvider == "llamacpp") {
                        Spacer(Modifier.height(16.dp))
                        Text("Recommended Mobile Models (Direct Download)", style = MaterialTheme.typography.labelMedium, color = Synapse)
                        Spacer(Modifier.height(8.dp))
                        
                        val recommendedModels = listOf(
                            Triple("Gemma 2 2B Instruct", "1.6 GB", "https://huggingface.co/bartowski/gemma-2-2b-it-GGUF/resolve/main/gemma-2-2b-it-Q4_K_M.gguf"),
                            Triple("Llama 3 8B Instruct", "4.9 GB", "https://huggingface.co/QuantFactory/Meta-Llama-3-8B-Instruct-GGUF/resolve/main/Meta-Llama-3-8B-Instruct.Q4_K_M.gguf"),
                            Triple("Phi-3 Mini 4K Instruct", "2.4 GB", "https://huggingface.co/microsoft/Phi-3-mini-4k-instruct-gguf/resolve/main/Phi-3-mini-4k-instruct-q4.gguf"),
                            Triple("Qwen2 1.5B Instruct", "1.0 GB", "https://huggingface.co/Qwen/Qwen2-1.5B-Instruct-GGUF/resolve/main/qwen2-1_5b-instruct-q4_k_m.gguf")
                        )
                        
                        recommendedModels.forEach { (name, size, url) ->
                            val filename = url.substringAfterLast("/")
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(name, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                                    Text(size, style = MaterialTheme.typography.labelSmall, color = Muted)
                                }
                                TextButton(
                                    onClick = { onDownloadGguf(context, url, filename) },
                                    colors = ButtonDefaults.textButtonColors(contentColor = Synapse)
                                ) {
                                    Text("Download")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
