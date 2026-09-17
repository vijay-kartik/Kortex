package dev.kortex.app.ui.util

import dev.kortex.app.domain.chat.ChatTurn
import dev.kortex.app.domain.chat.ReasoningLine
import dev.kortex.core.state.Message

/** Short lowercase source tags keep the trace legible: `llm`, `tool`, `react`, `reflect`… */
internal fun traceTag(tag: String) = when (tag) {
    "OpenAiProvider", "DeepseekProvider" -> "llm"
    "ToolGovernor" -> "tool"
    else -> tag.removeSuffix("Node").lowercase()
}

/**
 * The trace exactly as rendered in the expanded panel — one `tag  message` line
 * per step, nothing added. This is what `copy` and `share` emit.
 */
internal fun traceAsVisibleText(lines: List<ReasoningLine>) =
    lines.joinToString("\n") { "${traceTag(it.tag)}  ${it.message}" }

/**
 * The whole conversation as plain text — every bubble plus each assistant turn's full
 * reasoning trace — structured so it can be pasted into another agent for analysis.
 */
internal fun conversationAsText(turns: List<ChatTurn>): String = buildString {
    appendLine("# Kortex conversation — ${turns.size} turn${if (turns.size == 1) "" else "s"}")
    turns.forEach { turn ->
        val msg = turn.message
        appendLine()
        appendLine(if (msg.role == Message.Role.USER) "## User" else "## Kortex")
        msg.attachments.forEach { att ->
            if (att.mimeType.startsWith("audio/") && att.transcript != null) {
                appendLine("[voice note] ${att.transcript}")
            } else {
                appendLine("[attachment: ${att.filename ?: "file"} (${att.mimeType})]")
            }
        }
        if (msg.content.isNotBlank()) appendLine(msg.content)
        if (turn.reasoning.isNotEmpty()) {
            val s = turn.stats
            appendLine()
            appendLine(
                "### Trace — %d step%s · %,d tok · %d tool%s · %.1fs".format(
                    turn.reasoning.size,
                    if (turn.reasoning.size == 1) "" else "s",
                    s.tokensUsed,
                    s.toolCalls,
                    if (s.toolCalls == 1) "" else "s",
                    s.durationMs / 1000.0,
                )
            )
            appendLine(traceAsVisibleText(turn.reasoning))
        }
    }
}.trimEnd()

/** 67_000ms -> "1:07". */
internal fun formatVoiceDuration(ms: Long): String {
    val totalSec = ms / 1000
    return "%d:%02d".format(totalSec / 60, totalSec % 60)
}
