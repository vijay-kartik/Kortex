package dev.kortex.app.ui.screens.chat

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.hilt.navigation.compose.hiltViewModel
import dev.kortex.design.R
import dev.kortex.design.Edge
import dev.kortex.app.ui.components.ListeningBar
import dev.kortex.app.ui.components.MessageBubble
import dev.kortex.design.Mono
import dev.kortex.design.Muted
import dev.kortex.design.Panel
import dev.kortex.app.ui.components.PulsingDot
import dev.kortex.app.ui.components.ReasoningPanel
import dev.kortex.design.Synapse
import dev.kortex.design.SynapseDim
import dev.kortex.design.Void
import dev.kortex.app.ui.util.formatVoiceDuration
import dev.kortex.app.data.files.AttachmentRead
import dev.kortex.app.data.files.readAttachment
import dev.kortex.mvi.ObserveEffects
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * @param request session change from another screen; applied once, then [onRequestHandled] clears it.
 */
@Composable
fun ChatRoute(
    modifier: Modifier = Modifier,
    request: ChatRequest? = null,
    onRequestHandled: () -> Unit = {},
    viewModel: ChatViewModel = hiltViewModel(),
) {
    LaunchedEffect(request) {
        when (val pending = request ?: return@LaunchedEffect) {
            is ChatRequest.LoadSession -> viewModel.onIntent(ChatIntent.LoadSession(pending.sessionId))
            is ChatRequest.NewSession -> viewModel.onIntent(ChatIntent.NewSession(draft = pending.draft))
        }
        onRequestHandled()
    }

    val state by viewModel.state.collectAsStateWithLifecycle()
    var input by rememberSaveable { mutableStateOf("") }
    val context = LocalContext.current
    ObserveEffects(viewModel.effects) { effect ->
        when (effect) {
            is ChatEffect.ShowToast -> Toast.makeText(context, effect.message, Toast.LENGTH_SHORT).show()
            is ChatEffect.FillComposer -> input = effect.text
        }
    }
    ChatScreen(
        state = state,
        input = input,
        onInputChange = { input = it },
        onIntent = viewModel::onIntent,
        modifier = modifier,
    )
}

/** The composer's [input] is owned by the caller and only reaches the ViewModel in [ChatIntent.Send]. */
@Composable
fun ChatScreen(
    state: ChatState,
    input: String,
    onInputChange: (String) -> Unit,
    onIntent: (ChatIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()

    val context = LocalContext.current
    val contentResolver = context.contentResolver
    val coroutineScope = rememberCoroutineScope()

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris: List<Uri> ->
        coroutineScope.launch(Dispatchers.IO) {
            uris.forEach { uri ->
                val mimeType = contentResolver.getType(uri) ?: "application/octet-stream"
                when (val read = contentResolver.readAttachment(uri, mimeType)) {
                    is AttachmentRead.Ok -> withContext(Dispatchers.Main) {
                        onIntent(ChatIntent.StageAttachment(read.attachment))
                    }
                    is AttachmentRead.TooLarge -> withContext(Dispatchers.Main) {
                        Toast.makeText(context, read.message, Toast.LENGTH_LONG).show()
                    }
                    AttachmentRead.Unreadable -> Unit
                }
            }
        }
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            onIntent(ChatIntent.StartVoice)
        } else {
            Toast.makeText(context, "Microphone permission is required for voice input.", Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(state.turns.size) {
        if (state.turns.isNotEmpty()) listState.animateScrollToItem(state.turns.lastIndex)
    }

    state.pendingApproval?.let { pending ->
        AlertDialog(
            onDismissRequest = { onIntent(ChatIntent.ResolveApproval(false)) },
            title = { Text("Approve action?") },
            text = { Text(pending, fontFamily = Mono, fontSize = 12.sp) },
            confirmButton = { TextButton(onClick = { onIntent(ChatIntent.ResolveApproval(true)) }) { Text("Allow") } },
            dismissButton = { TextButton(onClick = { onIntent(ChatIntent.ResolveApproval(false)) }) { Text("Deny") } },
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .imePadding()
            .padding(horizontal = 14.dp),
    ) {
        if (state.showEmptyState) {
            EmptyState(Modifier.weight(1f).fillMaxWidth())
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(vertical = 12.dp),
            ) {
                items(state.turns) { turn -> MessageBubble(turn) }
            }
        }

        // Live trace: the agent's routing, LLM calls, tool calls, and reflection verdicts
        // stream in here while it works, then fold into the finished answer's own panel.
        if (state.busy && state.liveReasoning.isNotEmpty()) {
            ReasoningPanel(
                lines = state.liveReasoning,
                stats = state.liveStats,
                live = true,
                modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
            )
        }

        if (state.busy) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PulsingDot(7.dp)
                Text(
                    state.busyLine,
                    style = MaterialTheme.typography.labelSmall,
                    color = Muted,
                )
            }
        }

        if (state.stagedAttachments.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                state.stagedAttachments.forEachIndexed { index, att ->
                    val isVoice = att.mimeType.startsWith("audio/") && att.transcript != null
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (isVoice) SynapseDim else Panel,
                        border = BorderStroke(1.dp, if (isVoice) Synapse.copy(alpha = 0.4f) else Edge),
                        modifier = Modifier.clickable { onIntent(ChatIntent.RemoveAttachment(index)) }
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

        if (state.voice !is VoiceState.Idle) {
            ListeningBar(
                state = state.voice,
                onCancel = { onIntent(ChatIntent.CancelVoice) },
                onDone = { onIntent(ChatIntent.StopVoice) },
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
                    enabled = !state.busy,
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
                        onValueChange = onInputChange,
                        placeholder = { Text("Message Kortex…", color = Muted) },
                        enabled = !state.busy,
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
                if (state.canSend(input)) {
                    FilledIconButton(
                        onClick = { onIntent(ChatIntent.Send(input)); onInputChange("") },
                        enabled = !state.busy,
                        shape = CircleShape,
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = Synapse,
                            contentColor = Void,
                            disabledContainerColor = Panel,
                            disabledContentColor = Muted,
                        ),
                        modifier = Modifier.size(52.dp),
                    ) {
                        Icon(
                            painterResource(R.drawable.ic_send),
                            contentDescription = "Send",
                            modifier = Modifier.size(22.dp),
                        )
                    }
                } else {
                    FilledIconButton(
                        onClick = {
                            if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                                onIntent(ChatIntent.StartVoice)
                            } else {
                                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        },
                        enabled = !state.busy,
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

        state.modelFooter?.let { footer ->
            Text(
                text = footer,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                color = Muted.copy(alpha = 0.7f),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)
            )
        }
    }
}
