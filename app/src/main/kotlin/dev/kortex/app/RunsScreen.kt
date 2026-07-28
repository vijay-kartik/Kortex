package dev.kortex.app

import android.app.Application
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.kortex.app.ui.Alarm
import dev.kortex.app.ui.Amber
import dev.kortex.app.ui.Edge
import dev.kortex.app.ui.Mono
import dev.kortex.app.ui.Muted
import dev.kortex.app.ui.Panel
import dev.kortex.app.ui.Synapse
import dev.kortex.core.observability.AgentRun
import dev.kortex.core.observability.AgentRunSummary
import dev.kortex.core.observability.LogLine
import dev.kortex.core.observability.RunSpan
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Backs the dedicated Runs screen. Reads only `dev.kortex.core.observability`
 * types via the store from [KortexContainer] — no coupling to chat-screen classes.
 */
class RunsViewModel(application: Application) : AndroidViewModel(application) {
    private val store = (application as KortexApp).container.runTraceStore

    val summaries: StateFlow<List<AgentRunSummary>> =
        store.observeSummaries().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _selected = MutableStateFlow<AgentRun?>(null)
    val selected: StateFlow<AgentRun?> = _selected.asStateFlow()

    fun open(id: String) { viewModelScope.launch { _selected.value = store.get(id) } }
    fun close() { _selected.value = null }
    fun clearAll() { viewModelScope.launch { store.clear(); _selected.value = null } }
}

@Composable
fun RunsScreen(vm: RunsViewModel = viewModel()) {
    val summaries by vm.summaries.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()

    val current = selected
    if (current == null) {
        RunList(summaries, onOpen = vm::open, onClear = vm::clearAll)
    } else {
        val ids = summaries.map { it.id }
        val idx = ids.indexOf(current.id)
        RunDetail(
            run = current,
            hasNewer = idx > 0,
            hasOlder = idx in 0 until ids.lastIndex,
            onNewer = { if (idx > 0) vm.open(ids[idx - 1]) },
            onOlder = { if (idx in 0 until ids.lastIndex) vm.open(ids[idx + 1]) },
            onBack = vm::close,
        )
    }
}

// ── List ─────────────────────────────────────────────────────────────────────

@Composable
private fun RunList(runs: List<AgentRunSummary>, onOpen: (String) -> Unit, onClear: () -> Unit) {
    if (runs.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No agent runs yet.", color = Muted, style = MaterialTheme.typography.bodyMedium)
        }
        return
    }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "${runs.size} run${if (runs.size == 1) "" else "s"}",
                color = Muted, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onClear) { Text("Clear", color = Alarm) }
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(runs, key = { it.id }) { RunRow(it, onOpen) }
        }
    }
}

@Composable
private fun RunRow(run: AgentRunSummary, onOpen: (String) -> Unit) {
    Surface(
        shape = RoundedCornerShape(10.dp), color = Panel, border = BorderStroke(1.dp, Edge),
        modifier = Modifier.fillMaxWidth().clickable { onOpen(run.id) },
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(run.status)
                Spacer(Modifier.width(8.dp))
                Text(
                    run.query.ifBlank { "(no query)" },
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f),
                )
                Text(relativeTime(run.startedAt), color = Muted, style = MaterialTheme.typography.labelSmall)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                statsLine(run.steps, run.totalTokens, run.toolCalls, run.durationMs),
                color = Synapse.copy(alpha = 0.8f), style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

// ── Detail ───────────────────────────────────────────────────────────────────

@Composable
private fun RunDetail(
    run: AgentRun,
    hasNewer: Boolean,
    hasOlder: Boolean,
    onNewer: () -> Unit,
    onOlder: () -> Unit,
    onBack: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text("‹ Runs", color = Synapse) }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onNewer, enabled = hasNewer) {
                Text("Newer", color = if (hasNewer) Synapse else Muted)
            }
            TextButton(onClick = onOlder, enabled = hasOlder) {
                Text("Older", color = if (hasOlder) Synapse else Muted)
            }
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { RunHeader(run) }
            item {
                Text("TIMELINE", color = Muted, style = MaterialTheme.typography.labelSmall)
            }
            items(run.spans) { SpanRow(it) }
            if (run.logLines.isNotEmpty()) {
                item { LogSection(run.logLines) }
            }
        }
    }
}

@Composable
private fun RunHeader(run: AgentRun) {
    Surface(shape = RoundedCornerShape(10.dp), color = Panel, border = BorderStroke(1.dp, Edge)) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Text(run.query.ifBlank { "(no query)" }, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(run.status)
                Spacer(Modifier.width(6.dp))
                Text(run.status.name, color = statusColor(run.status), style = MaterialTheme.typography.labelSmall)
                run.route?.let {
                    Text(" · $it", color = Muted, style = MaterialTheme.typography.labelSmall)
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                statsLine(run.stats.steps, run.stats.totalTokens, run.stats.toolCalls, run.stats.durationMs) +
                    " · ${run.stats.llmCalls} llm",
                color = Synapse.copy(alpha = 0.8f), style = MaterialTheme.typography.labelSmall,
            )
            if (!run.answer.isNullOrBlank()) {
                Spacer(Modifier.height(8.dp))
                Text("ANSWER", color = Muted, style = MaterialTheme.typography.labelSmall)
                Text(run.answer!!, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun SpanRow(span: RunSpan) {
    var expanded by remember { mutableStateOf(false) }
    val hasDetail = span.llm != null || span.tool != null
    Surface(
        shape = RoundedCornerShape(8.dp), color = Panel, border = BorderStroke(1.dp, Edge),
        modifier = Modifier.fillMaxWidth().clickable(enabled = hasDetail) { expanded = !expanded },
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    span.kind.name,
                    color = kindColor(span),
                    fontFamily = Mono, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.width(58.dp),
                )
                Text(
                    span.name,
                    style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
                Text(formatDuration(span.durationMs), color = Muted, fontFamily = Mono, fontSize = 10.sp)
            }
            if (span.detail.isNotBlank()) {
                Text(
                    span.detail, color = if (span.ok) Muted else Alarm,
                    fontFamily = Mono, fontSize = 11.sp, maxLines = if (expanded) Int.MAX_VALUE else 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 58.dp, top = 2.dp),
                )
            }
            if (expanded) {
                span.llm?.let {
                    MonoDetail("${it.inputTokens} in / ${it.outputTokens} out · ${it.model}")
                }
                span.tool?.let {
                    MonoDetail("args ${it.argumentsJson}")
                    MonoDetail("decision ${it.decision}")
                }
            }
        }
    }
}

@Composable
private fun MonoDetail(text: String) {
    Text(
        text, color = Synapse.copy(alpha = 0.85f), fontFamily = Mono, fontSize = 11.sp,
        modifier = Modifier.padding(start = 58.dp, top = 2.dp),
    )
}

@Composable
private fun LogSection(lines: List<LogLine>) {
    var expanded by remember { mutableStateOf(false) }
    Surface(shape = RoundedCornerShape(8.dp), color = Panel, border = BorderStroke(1.dp, Edge)) {
        Column(Modifier.fillMaxWidth().padding(10.dp)) {
            Row(
                Modifier.fillMaxWidth().clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("LOG (${lines.size} lines)", color = Muted, style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                Text(if (expanded) "▲" else "▼", color = Muted, fontSize = 10.sp)
            }
            if (expanded) {
                Spacer(Modifier.height(6.dp))
                lines.forEach { line ->
                    Text(
                        "${line.tag}: ${line.message}",
                        color = logColor(line.level), fontFamily = Mono, fontSize = 10.sp,
                        modifier = Modifier.padding(vertical = 1.dp),
                    )
                }
            }
        }
    }
}

// ── Bits ─────────────────────────────────────────────────────────────────────

@Composable
private fun StatusDot(status: AgentRun.Status) {
    Box(Modifier.size(8.dp).background(statusColor(status), CircleShape))
}

private fun statusColor(status: AgentRun.Status): Color = when (status) {
    AgentRun.Status.COMPLETED -> Synapse
    AgentRun.Status.FAILED -> Alarm
    AgentRun.Status.CANCELLED -> Amber
}

private fun kindColor(span: RunSpan): Color = when (span.kind) {
    RunSpan.Kind.LLM -> Synapse
    RunSpan.Kind.TOOL -> if (span.ok) Amber else Alarm
    RunSpan.Kind.ERROR -> Alarm
    RunSpan.Kind.VERDICT -> Muted
    RunSpan.Kind.NODE -> Muted
}

private fun logColor(level: String): Color = when (level) {
    "ERROR" -> Alarm
    "WARN" -> Amber
    else -> Synapse.copy(alpha = 0.75f)
}

private fun statsLine(steps: Int, tokens: Int, tools: Int, durationMs: Long): String =
    "$steps step${if (steps == 1) "" else "s"} · ${formatTokens(tokens)} tok · " +
        "$tools tool${if (tools == 1) "" else "s"} · ${formatDuration(durationMs)}"

private fun formatTokens(tokens: Int): String =
    if (tokens >= 1000) "%.1fk".format(tokens / 1000.0) else tokens.toString()

private fun formatDuration(ms: Long): String = when {
    ms < 1000 -> "${ms}ms"
    else -> "%.1fs".format(ms / 1000.0)
}

private fun relativeTime(atMillis: Long): String {
    val diff = System.currentTimeMillis() - atMillis
    return when {
        diff < 60_000 -> "just now"
        diff < 3_600_000 -> "${diff / 60_000}m ago"
        diff < 86_400_000 -> "${diff / 3_600_000}h ago"
        else -> "${diff / 86_400_000}d ago"
    }
}
