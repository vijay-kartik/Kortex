package dev.kortex.app.ui.components

import android.graphics.BitmapFactory
import android.util.Base64
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.halilibo.richtext.markdown.Markdown
import com.halilibo.richtext.ui.material3.RichText
import dev.kortex.app.ChatTurn
import dev.kortex.app.PdfViewer
import dev.kortex.app.convertTableToTsv
import dev.kortex.app.parseMarkdownTables
import dev.kortex.app.ui.Alarm
import dev.kortex.app.ui.Edge
import dev.kortex.app.ui.Muted
import dev.kortex.app.ui.Panel
import dev.kortex.app.ui.Synapse
import dev.kortex.app.ui.Void
import dev.kortex.core.state.Attachment
import dev.kortex.core.state.Message

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun MessageBubble(turn: ChatTurn) {
    val msg = turn.message
    val isUser = msg.role == Message.Role.USER
    val clipboard = LocalClipboardManager.current
    val haptics = LocalHapticFeedback.current
    val context = LocalContext.current

    val parsed = remember(msg.content) { parseMarkdownTables(msg.content) }
    var selectedTable by remember { mutableStateOf<String?>(null) }
    var selectedAttachment by remember { mutableStateOf<Attachment?>(null) }

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
                            // Voice-only messages have blank content; copy the transcript instead.
                            val copyText = msg.content.ifBlank {
                                msg.attachments.mapNotNull { it.transcript }.joinToString("\n")
                            }
                            clipboard.setText(AnnotatedString(copyText))
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                        },
                    ),
            ) {
                if (isUser) {
                    val voiceNotes = msg.attachments.filter { it.mimeType.startsWith("audio/") }
                    val fileAttachments = msg.attachments - voiceNotes.toSet()
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                        voiceNotes.forEachIndexed { i, att ->
                            VoiceNoteContent(
                                att = att,
                                modifier = Modifier.padding(top = if (i > 0) 8.dp else 0.dp),
                            )
                        }
                        if (fileAttachments.isNotEmpty()) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.padding(top = if (voiceNotes.isNotEmpty()) 8.dp else 0.dp)
                            ) {
                                fileAttachments.forEach { att ->
                                    Surface(
                                        color = Void.copy(alpha = 0.2f),
                                        shape = RoundedCornerShape(6.dp),
                                        modifier = Modifier.clickable { selectedAttachment = att }
                                    ) {
                                        Text(
                                            text = "📎 ${att.filename ?: "File"}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = Void,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)
                                        )
                                    }
                                }
                            }
                        }
                        if (msg.content.isNotBlank()) {
                            if (msg.attachments.isNotEmpty()) {
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 8.dp)
                                        .height(1.dp)
                                        .background(Void.copy(alpha = 0.2f))
                                )
                            }
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
                            val bytes = Base64.decode(att.dataBase64, Base64.DEFAULT)
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
