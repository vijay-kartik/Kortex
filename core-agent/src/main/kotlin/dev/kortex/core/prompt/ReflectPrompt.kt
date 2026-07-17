package dev.kortex.core.prompt

import dev.kortex.core.state.Message

/**
 * One tool call the assistant made during the run, paired with the recorded result
 * content of its TOOL-role message (matched by toolCallId). See [ReflectPrompt.pair].
 */
data class ToolExchange(
    val name: String,
    val argumentsJson: String,
    val result: String,
)

/**
 * Critique prompt for [dev.kortex.core.pattern.ReflectNode] (pattern 4: Reflection).
 *
 * T2.1: the reviewer sees the tool RESULTS, not just the call names, so it can verify
 * the answer's claims against what the tools actually returned (F7). Each result is
 * truncated to [PER_RESULT_CHARS] and the whole tool section is capped at
 * [TOOL_SECTION_CHARS], keeping the NEWEST exchanges (they usually ground the answer).
 *
 * T2.2: explicit pass/fail rubric (F9) instead of the vague "fully and correctly";
 * style/tone/length/formatting are explicitly not revision-worthy, and a REVISE must
 * name the failed criterion. The reply format stays machine-parseable
 * ("OK" / "REVISE: <feedback>") — ReflectNode's parsing is unchanged.
 */
object ReflectPrompt {

    /** Per-result truncation budget (chars), cut at a word boundary. */
    internal const val PER_RESULT_CHARS = 500

    /** Budget (chars) for the whole rendered tool section; oldest exchanges drop first. */
    internal const val TOOL_SECTION_CHARS = 3_000

    /** Result placeholder for a tool call whose TOOL message never arrived. */
    const val NO_RESULT = "(no result recorded)"

    fun build(
        toolExchanges: List<ToolExchange>,
        request: String,
        answer: String,
        attachmentNote: String = "",
    ): String = listOf(
        "You are a reviewer verifying the assistant's final answer before it reaches the user.",
        "The assistant has real, live tools (web search, opening URLs, the device clock);",
        "facts in its answer may come from the tool results below, which are current and",
        "trustworthy even when they postdate your training data. Never reject an answer",
        "because its dates are later than what you know, and never claim the assistant",
        "cannot search the web or access real-time information — it can." + attachmentNote,
        "",
        "Check the answer's claims against the tool results below. The answer passes only if:",
        "(a) it is factually consistent with the tool results shown,",
        "(b) it actually answers what was asked,",
        "(c) nothing the user explicitly requested is missing.",
        "Do NOT request revision for style, tone, length, or formatting — concise answers",
        "are preferred and must not be penalized.",
        "- If every criterion passes, reply with exactly: OK",
        "- Otherwise reply: REVISE: (<failed criterion a/b/c>) <specific, actionable feedback>",
        "",
        "Tool calls and results from this run:",
        renderToolSection(toolExchanges),
        "",
        "User request:",
        request,
        "",
        "Assistant answer:",
        answer,
    ).joinToString("\n")

    /**
     * Pairs each tool call in [messages] (in call order) with the content of the TOOL
     * message carrying the same toolCallId. Calls with no recorded result get [NO_RESULT].
     */
    fun pair(messages: List<Message>): List<ToolExchange> {
        val resultsById = HashMap<String, String>()
        for (m in messages) {
            if (m.role == Message.Role.TOOL && m.toolCallId != null) {
                resultsById.putIfAbsent(m.toolCallId, m.content)
            }
        }
        return messages.flatMap { it.toolCalls }.map { call ->
            ToolExchange(call.name, call.argumentsJson, resultsById[call.id] ?: NO_RESULT)
        }
    }

    /**
     * Renders the "call + result" section. Each result is truncated to [perResultChars];
     * the whole section is capped at [totalChars] by dropping the OLDEST exchanges first
     * (the newest results usually ground the final answer). The newest exchange is always
     * kept. A leading marker notes how many older calls were omitted.
     */
    internal fun renderToolSection(
        exchanges: List<ToolExchange>,
        perResultChars: Int = PER_RESULT_CHARS,
        totalChars: Int = TOOL_SECTION_CHARS,
    ): String {
        if (exchanges.isEmpty()) return "(none)"
        val entries = exchanges.map { e ->
            "- ${e.name}(${e.argumentsJson})\n  Result: ${truncateAtWord(e.result, perResultChars)}"
        }
        // Keep the newest suffix that fits the total budget (newest entry always kept).
        val kept = ArrayDeque<String>()
        var used = 0
        for (entry in entries.asReversed()) {
            val cost = entry.length + (if (kept.isEmpty()) 0 else 1) // +1 for the joining \n
            if (kept.isNotEmpty() && used + cost > totalChars) break
            kept.addFirst(entry)
            used += cost
        }
        val omitted = entries.size - kept.size
        val header = if (omitted > 0) "($omitted older tool call(s) omitted)\n" else ""
        return header + kept.joinToString("\n")
    }

    /** Truncates [text] to at most [maxChars] at a word boundary, appending an ellipsis. */
    internal fun truncateAtWord(text: String, maxChars: Int): String {
        if (text.length <= maxChars) return text
        val cut = text.take(maxChars)
        val boundary = cut.indexOfLast { it.isWhitespace() }
        return (if (boundary > 0) cut.substring(0, boundary) else cut).trimEnd() + "…"
    }
}
