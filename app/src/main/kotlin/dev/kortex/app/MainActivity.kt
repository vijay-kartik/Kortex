package dev.kortex.app

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.kortex.app.ui.Alarm
import dev.kortex.app.ui.Amber
import dev.kortex.app.ui.Edge
import dev.kortex.app.ui.KortexTheme
import dev.kortex.app.ui.Mono
import dev.kortex.app.ui.Muted
import dev.kortex.app.ui.Panel
import dev.kortex.app.ui.Synapse
import dev.kortex.app.ui.SynapseDim
import dev.kortex.app.ui.Void
import dev.kortex.core.log.Logger
import dev.kortex.core.state.Message
import com.halilibo.richtext.markdown.Markdown
import com.halilibo.richtext.ui.material3.RichText
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.horizontalScroll
import android.graphics.BitmapFactory
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The theme is committed dark, so pin light system-bar icons regardless of device theme.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        setContent { KortexTheme { RootScreen() } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RootScreen() {
    var tab by remember { mutableIntStateOf(0) }
    var showMcpSettings by remember { mutableStateOf(false) }
    val tabs = listOf("Chat", "Cards", "Context", "History")
    val vm: ChatViewModel = viewModel()

    if (showMcpSettings) {
        McpSettingsScreen(onDismiss = { showMcpSettings = false })
    } else {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                Column {
                    TopAppBar(
                        title = { Wordmark() },
                        actions = {
                            if (tab == 0) {
                                IconButton(onClick = { showMcpSettings = true }) {
                                    Icon(
                                        painterResource(R.drawable.ic_tune),
                                        contentDescription = "MCP Settings",
                                        tint = Muted,
                                    )
                                }
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.background,
                        ),
                    )
                    TabRow(
                        selectedTabIndex = tab,
                        containerColor = MaterialTheme.colorScheme.background,
                    ) {
                        tabs.forEachIndexed { i, title ->
                            Tab(
                                selected = tab == i,
                                onClick = { tab = i },
                                selectedContentColor = Synapse,
                                unselectedContentColor = Muted,
                                text = { Text(title, style = MaterialTheme.typography.labelLarge) },
                            )
                        }
                    }
                }
            },
        ) { innerPadding ->
            Box(Modifier.padding(innerPadding)) {
                when (tab) {
                    0 -> ChatScreen(vm = vm)
                    1 -> CardsScreen()
                    2 -> ContextScreen()
                    else -> HistoryScreen(
                        vm = vm,
                        onSelectSession = { sessionId ->
                            vm.loadSession(sessionId)
                            tab = 0
                        }
                    )
                }
            }
        }
    }
}

/** Status light + tracked-out wordmark: the app reads as an instrument, not a toy. */
@Composable
private fun Wordmark() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Image(
            painterResource(R.drawable.ic_kortex_mark),
            contentDescription = null,
            modifier = Modifier.size(18.dp),
        )
        Text(
            "KORTEX",
            style = MaterialTheme.typography.titleMedium.copy(
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 4.sp,
            ),
        )
    }
}

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
                    val base64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                    var filename: String? = null
                    contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                            if (nameIndex >= 0) {
                                filename = cursor.getString(nameIndex)
                            }
                        }
                    }
                    vm.stageAttachment(
                        dev.kortex.core.state.Attachment(
                            mimeType = mimeType,
                            dataBase64 = base64,
                            filename = filename
                        )
                    )
                }
            }
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
                    (ui.status ?: "working…").lowercase(),
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
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Panel,
                        border = BorderStroke(1.dp, Edge),
                        modifier = Modifier.clickable { vm.removeStagedAttachment(index) }
                    ) {
                        Text(
                            text = (att.filename ?: "File") + " ✕",
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                            color = Muted
                        )
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
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
            FilledIconButton(
                onClick = { vm.send(input); input = "" },
                enabled = !ui.busy && (input.isNotBlank() || stagedAttachments.isNotEmpty()),
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
        }
    }
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PulsingDot(10.dp)
            Text("Kortex is listening", style = MaterialTheme.typography.titleMedium)
            Text(
                "It can search the web, open pages,\ndo math, and check the time.",
                style = MaterialTheme.typography.bodySmall,
                color = Muted,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(turn: ChatTurn) {
    val msg = turn.message
    val isUser = msg.role == Message.Role.USER
    val clipboard = LocalClipboardManager.current
    val haptics = LocalHapticFeedback.current
    val context = LocalContext.current

    val parsed = remember(msg.content) { parseMarkdownTables(msg.content) }
    var selectedTable by remember { mutableStateOf<String?>(null) }
    var selectedAttachment by remember { mutableStateOf<dev.kortex.core.state.Attachment?>(null) }

    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
        ) {
            Surface(
                color = if (isUser) Synapse else Panel,
                contentColor = if (isUser) Void else MaterialTheme.colorScheme.onSurface,
                border = if (isUser) null else BorderStroke(1.dp, Edge),
                // Asymmetric radii point each bubble back at its speaker.
                shape = if (isUser) RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp)
                else RoundedCornerShape(18.dp, 18.dp, 18.dp, 4.dp),
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
                if (isUser) {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                        if (msg.attachments.isNotEmpty()) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.padding(bottom = if (msg.content.isNotBlank()) 6.dp else 0.dp)
                            ) {
                                msg.attachments.forEach { att ->
                                    Surface(
                                        color = Void.copy(alpha = 0.2f),
                                        shape = RoundedCornerShape(6.dp),
                                        modifier = Modifier.clickable { selectedAttachment = att }
                                    ) {
                                        Text(
                                            text = "\uD83D\uDCCE ${att.filename ?: "File"}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = Void,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)
                                        )
                                    }
                                }
                            }
                        }
                        if (msg.content.isNotBlank()) {
                            Text(
                                msg.content,
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                    }
                } else {
                    Column {
                        if (parsed.text.isNotEmpty()) {
                            RichText(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                                Markdown(content = parsed.text)
                            }
                        }
                        if (parsed.tables.isNotEmpty()) {
                            parsed.tables.forEachIndexed { index, table ->
                                TextButton(
                                    onClick = { selectedTable = table },
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp)
                                ) {
                                    Text("Show Table ${if (parsed.tables.size > 1) index + 1 else ""}", color = Synapse)
                                }
                            }
                        }
                    }
                }
            }
        }
        if (turn.reasoning.isNotEmpty()) {
            ReasoningPanel(
                lines = turn.reasoning,
                stats = turn.stats,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            )
        }
    }
    
    selectedTable?.let { table ->
        Dialog(
            onDismissRequest = { selectedTable = null },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(
                modifier = Modifier.fillMaxSize().systemBarsPadding(),
                color = Panel
            ) {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(onClick = { selectedTable = null }) {
                            Text("Close", color = Synapse)
                        }
                        Text("Data Table", style = MaterialTheme.typography.titleMedium, color = Void)
                        TextButton(onClick = { 
                            val tsv = convertTableToTsv(table)
                            clipboard.setText(AnnotatedString(tsv))
                            Toast.makeText(context, "Copied for Sheets/Excel", Toast.LENGTH_SHORT).show()
                        }) {
                            Text("Copy CSV", color = Synapse)
                        }
                    }
                    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                        RichText(
                            modifier = Modifier
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState())
                                .padding(16.dp)
                        ) {
                            Markdown(content = table)
                        }
                    }
                }
            }
        }
    }
    
    selectedAttachment?.let { att ->
        Dialog(
            onDismissRequest = { selectedAttachment = null },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(
                modifier = Modifier.fillMaxSize().systemBarsPadding(),
                color = Panel
            ) {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(onClick = { selectedAttachment = null }) {
                            Text("Close", color = Synapse)
                        }
                        Text(att.filename ?: "Attachment", style = MaterialTheme.typography.titleMedium, color = Void)
                        Spacer(modifier = Modifier.width(64.dp)) // Balance the title
                    }
                    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) {
                        if (att.mimeType.startsWith("image/")) {
                            val bytes = android.util.Base64.decode(att.dataBase64, android.util.Base64.DEFAULT)
                            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                            if (bitmap != null) {
                                Image(
                                    bitmap = bitmap.asImageBitmap(),
                                    contentDescription = att.filename,
                                    modifier = Modifier.fillMaxSize().padding(16.dp),
                                    contentScale = ContentScale.Fit
                                )
                            } else {
                                Text("Failed to load image.", color = Alarm)
                            }
                        } else if (att.mimeType == "application/pdf") {
                            PdfViewer(
                                base64 = att.dataBase64,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Text("Preview not supported for ${att.mimeType}.", color = Muted)
                        }
                    }
                }
            }
        }
    }
}

/**
 * The signature element: the agent's chain of thought rendered as a trace readout —
 * synapse rail, mono log lines, and a telemetry strip (tokens · tools · time) that
 * stays visible whether the trace is expanded or collapsed. While [live], it pulses.
 */
@Composable
private fun ReasoningPanel(
    lines: List<ReasoningLine>,
    stats: ReasoningStats,
    modifier: Modifier = Modifier,
    live: Boolean = false,
) {
    var expanded by remember { mutableStateOf(live) }
    Row(modifier.height(IntrinsicSize.Min)) {
        Box(
            Modifier
                .width(2.dp)
                .fillMaxHeight()
                .background(Brush.verticalGradient(listOf(Synapse, SynapseDim))),
        )
        Column(Modifier.padding(start = 10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (live) PulsingDot(6.dp)
                Text("TRACE", style = MaterialTheme.typography.labelSmall, color = Synapse)
                Text(
                    "${lines.size} step${if (lines.size == 1) "" else "s"}",
                    style = MaterialTheme.typography.labelSmall,
                    color = Muted,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    if (expanded) "hide" else "show",
                    style = MaterialTheme.typography.labelSmall,
                    color = Muted,
                )
            }
            if (expanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 220.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(top = 6.dp),
                ) {
                    lines.forEach { line -> TraceLine(line) }
                }
            }
            Text(
                "%,d tok · %d tool%s · %.1fs".format(
                    stats.tokensUsed,
                    stats.toolCalls,
                    if (stats.toolCalls == 1) "" else "s",
                    stats.durationMs / 1000.0,
                ),
                style = MaterialTheme.typography.labelSmall,
                color = Muted,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

/** Short lowercase source tags keep the trace legible: `llm`, `tool`, `react`, `reflect`… */
private fun traceTag(tag: String) = when (tag) {
    "OpenAiProvider" -> "llm"
    "ToolGovernor" -> "tool"
    else -> tag.removeSuffix("Node").lowercase()
}

@Composable
private fun TraceLine(line: ReasoningLine) {
    val tagColor = when (line.level) {
        Logger.Level.ERROR -> Alarm
        Logger.Level.WARN -> Amber
        else -> Synapse.copy(alpha = 0.8f)
    }
    Text(
        buildAnnotatedString {
            withStyle(SpanStyle(color = tagColor, fontWeight = FontWeight.Medium)) {
                append(traceTag(line.tag))
            }
            append("  ")
            withStyle(SpanStyle(color = Muted)) { append(line.message) }
        },
        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.sp, lineHeight = 15.sp),
        modifier = Modifier.padding(vertical = 3.dp),
    )
}

@Composable
private fun PulsingDot(size: Dp, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "pulse")
    val alpha by transition.animateFloat(
        initialValue = 0.25f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "alpha",
    )
    Box(
        modifier
            .size(size)
            .graphicsLayer { this.alpha = alpha }
            .background(Synapse, CircleShape),
    )
}
