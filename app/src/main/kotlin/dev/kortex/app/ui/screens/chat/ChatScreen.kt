package dev.kortex.app.ui.screens.chat

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.kortex.app.ChatViewModel
import dev.kortex.app.R
import dev.kortex.app.VoiceState
import dev.kortex.app.ui.Edge
import dev.kortex.app.ui.components.ListeningBar
import dev.kortex.app.ui.components.MessageBubble
import dev.kortex.app.ui.Mono
import dev.kortex.app.ui.Muted
import dev.kortex.app.ui.Panel
import dev.kortex.app.ui.components.PulsingDot
import dev.kortex.app.ui.components.ReasoningPanel
import dev.kortex.app.ui.Synapse
import dev.kortex.app.ui.SynapseDim
import dev.kortex.app.ui.Void
import dev.kortex.app.util.formatVoiceDuration
import dev.kortex.core.state.Attachment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Composable
fun ChatScreen(modifier: Modifier = Modifier, vm: ChatViewModel = viewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    val stagedAttachments by vm.stagedAttachments.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val contentResolver = context.contentResolver
    val coroutineScope = rememberCoroutineScope()

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris: List<Uri> ->
        coroutineScope.launch(Dispatchers.IO) {
            uris.forEach { uri ->
                val mimeType = contentResolver.getType(uri) ?: "application/octet-stream"
                val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                if (bytes != null) {
                    val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                    var filename: String? = null
                    contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            if (nameIndex >= 0) {
                                filename = cursor.getString(nameIndex)
                            }
                        }
                    }
                    vm.stageAttachment(
                        Attachment(
                            mimeType = mimeType,
                            dataBase64 = base64,
                            filename = filename
                        )
                    )
                }
            }
        }
    }

    val voice by vm.voice.collectAsStateWithLifecycle()
    val voiceError by vm.voiceError.collectAsStateWithLifecycle()
    val draft by vm.draft.collectAsStateWithLifecycle()

    LaunchedEffect(draft) {
        draft?.let {
            input = it
            vm.consumeDraft()
        }
    }

    LaunchedEffect(voiceError) {
        voiceError?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            vm.consumeVoiceError()
        }
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            vm.startVoiceInput()
        } else {
            Toast.makeText(context, "Microphone permission is required for voice input.", Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(ui.turns.size) {
        if (ui.turns.isNotEmpty()) listState.animateScrollToItem(ui.turns.lastIndex)
    }

    ui.pendingApproval?.let { pending ->
        AlertDialog(
            onDismissRequest = { vm.resolveApproval(false) },
            title = { Text("Approve action?") },
            text = { Text(pending, fontFamily = Mono, fontSize = 12.sp) },
            confirmButton = { TextButton(onClick = { vm.resolveApproval(true) }) { Text("Allow") } },
            dismissButton = { TextButton(onClick = { vm.resolveApproval(false) }) { Text("Deny") } },
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .imePadding()
            .padding(horizontal = 14.dp),
    ) {
        if (ui.turns.isEmpty() && !ui.busy) {
            EmptyState(Modifier.weight(1f).fillMaxWidth())
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(vertical = 12.dp),
            ) {
                items(ui.turns) { turn -> MessageBubble(turn) }
            }
        }

        // Live trace: the agent's routing, LLM calls, tool calls, and reflection verdicts
        // stream in here while it works, then fold into the finished answer's own panel.
        if (ui.busy && ui.liveReasoning.isNotEmpty()) {
            ReasoningPanel(
                lines = ui.liveReasoning,
                stats = ui.liveStats,
                live = true,
                modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
            )
        }

        if (ui.busy) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PulsingDot(7.dp)
                Text(
                    buildString {
                        append((ui.status ?: "working…").lowercase())
                        if (ui.activeProvider != null) {
                            append(" • ${ui.activeProvider}")
                            if (ui.activeModel != null) {
                                append(" / ${ui.activeModel}")
                            }
                        }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = Muted,
                )
            }
        }

        if (stagedAttachments.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                stagedAttachments.forEachIndexed { index, att ->
                    val isVoice = att.mimeType.startsWith("audio/") && att.transcript != null
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (isVoice) SynapseDim else Panel,
                        border = BorderStroke(1.dp, if (isVoice) Synapse.copy(alpha = 0.4f) else Edge),
                        modifier = Modifier.clickable { vm.removeStagedAttachment(index) }
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                        ) {
                            if (isVoice) {
                                Icon(
                                    painterResource(R.drawable.ic_mic),
                                    contentDescription = null,
                                    tint = Synapse,
                                    modifier = Modifier.size(12.dp),
                                )
                            }
                            Text(
                                text = if (isVoice) {
                                    val t = att.transcript.orEmpty()
                                    t.take(24) + (if (t.length > 24) "…" else "") +
                                        (att.durationMs?.let { " · ${formatVoiceDuration(it)}" } ?: "") + " ✕"
                                } else {
                                    (att.filename ?: "File") + " ✕"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isVoice) Synapse else Muted,
                            )
                        }
                    }
                }
            }
        }

        if (voice !is VoiceState.Idle) {
            ListeningBar(
                state = voice,
                onCancel = { vm.cancelVoiceInput() },
                onDone = { vm.stopVoiceInput() },
                modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
            )
        } else {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                IconButton(
                    onClick = { filePickerLauncher.launch("*/*") },
                    enabled = !ui.busy,
                    modifier = Modifier.size(52.dp)
                ) {
                    Icon(
                        painterResource(R.drawable.ic_attach_file),
                        contentDescription = "Attach file",
                        tint = Muted
                    )
                }
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = Panel,
                    border = BorderStroke(1.dp, Edge),
                    modifier = Modifier.weight(1f),
                ) {
                    TextField(
                        value = input,
                        onValueChange = { input = it },
                        placeholder = { Text("Message Kortex…", color = Muted) },
                        enabled = !ui.busy,
                        maxLines = 4,
                        textStyle = MaterialTheme.typography.bodyLarge,
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            disabledContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            disabledIndicatorColor = Color.Transparent,
                            cursorColor = Synapse,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                // The trailing action morphs: mic when there's nothing to send (dictation is
                // an offer, in the dimmer accent), solid send once text or attachments exist.
                val canSend = input.isNotBlank() || stagedAttachments.isNotEmpty()
                if (canSend) {
                    FilledIconButton(
                        onClick = { vm.send(input); input = "" },
                        enabled = !ui.busy,
                        shape = CircleShape,
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = Synapse,
                            contentColor = Void,
                            disabledContainerColor = Panel,
                            disabledContentColor = Muted,
                        ),
                        modifier = Modifier.size(52.dp),
                    ) {
                        Text("↑", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    }
                } else {
                    FilledIconButton(
                        onClick = {
                            if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                                vm.startVoiceInput()
                            } else {
                                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        },
                        enabled = !ui.busy,
                        shape = CircleShape,
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = SynapseDim,
                            contentColor = Synapse,
                            disabledContainerColor = Panel,
                            disabledContentColor = Muted,
                        ),
                        modifier = Modifier.size(52.dp),
                    ) {
                        Icon(
                            painterResource(R.drawable.ic_mic),
                            contentDescription = "Voice input",
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
            }
        }

        if (ui.activeModel != null) {
            val providerName = when (ui.activeProvider) {
                "ollama" -> "Ollama"
                "ollama-cloud" -> "Ollama Cloud"
                else -> "OpenAI"
            }
            Text(
                text = "${ui.activeModel} • $providerName",
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                color = Muted.copy(alpha = 0.7f),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)
            )
        }
    }
}
