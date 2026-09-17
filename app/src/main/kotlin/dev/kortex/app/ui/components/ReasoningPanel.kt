package dev.kortex.app.ui.components

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kortex.app.ReasoningLine
import dev.kortex.app.ReasoningStats
import dev.kortex.app.ui.Alarm
import dev.kortex.app.ui.Amber
import dev.kortex.app.ui.Muted
import dev.kortex.app.ui.Synapse
import dev.kortex.app.ui.SynapseDim
import dev.kortex.app.util.traceAsVisibleText
import dev.kortex.app.util.traceTag
import dev.kortex.core.log.Logger

/**
 * The signature element: the agent's chain of thought rendered as a trace readout —
 * synapse rail, mono log lines, and a telemetry strip (tokens · tools · time) that
 * stays visible whether the trace is expanded or collapsed. While [live], it pulses.
 */
@Composable
internal fun ReasoningPanel(
    lines: List<ReasoningLine>,
    stats: ReasoningStats,
    modifier: Modifier = Modifier,
    live: Boolean = false,
) {
    var expanded by remember { mutableStateOf(live) }
    val clipboard = LocalClipboardManager.current
    val haptics = LocalHapticFeedback.current
    val context = LocalContext.current
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
                if (expanded) {
                    Text(
                        "copy",
                        style = MaterialTheme.typography.labelSmall,
                        color = Muted,
                        modifier = Modifier.clickable {
                            clipboard.setText(AnnotatedString(traceAsVisibleText(lines)))
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            Toast.makeText(context, "Trace copied", Toast.LENGTH_SHORT).show()
                        },
                    )
                    Text(
                        "share",
                        style = MaterialTheme.typography.labelSmall,
                        color = Muted,
                        modifier = Modifier.clickable {
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, traceAsVisibleText(lines))
                            }
                            context.startActivity(Intent.createChooser(send, "Share trace"))
                        },
                    )
                }
                Text(
                    if (expanded) "hide" else "show",
                    style = MaterialTheme.typography.labelSmall,
                    color = Muted,
                )
            }
            if (expanded) {
                // Selectable so individual lines can be long-press copied like any text.
                SelectionContainer {
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
