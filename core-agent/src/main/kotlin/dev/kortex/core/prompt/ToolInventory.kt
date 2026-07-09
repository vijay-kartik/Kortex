package dev.kortex.core.prompt

import dev.kortex.core.tool.Tool
import dev.kortex.core.tool.ToolRegistry

/**
 * Renders the live tool registry as a short system-prompt section, one bullet per tool,
 * so the prompt always matches what is actually registered — renaming or adding a tool
 * (built-in or MCP) updates the prompt with zero prompt-file edits. Only [Tool.name],
 * [Tool.description], and the optional [Tool.promptHint] are used, so any Tool works.
 */
object ToolInventory {

    /** Descriptions longer than this are cut at a word boundary. The full description
     *  still reaches the model through the function-calling schema; this bullet only
     *  needs to orient it. [Tool.promptHint]s are never truncated. */
    internal const val MAX_DESCRIPTION_CHARS = 100

    /** Renders the enabled tools of [registry]; empty string when there are none. */
    fun render(registry: ToolRegistry): String = render(registry.all())

    /**
     * Renders [tools] as a header plus `- <name>: <one-line description>` bullets, with
     * each tool's [Tool.promptHint] (if any) appended to its bullet. Returns "" for an
     * empty list so callers can skip the section entirely.
     */
    fun render(tools: List<Tool>): String {
        if (tools.isEmpty()) return ""
        return tools.joinToString(separator = "\n", prefix = "Tools available to you:\n") { tool ->
            val bullet = "- ${tool.name}: ${truncateAtWord(tool.description.oneLine())}"
            val hint = tool.promptHint?.oneLine()
            if (hint.isNullOrEmpty()) bullet else "$bullet $hint"
        }
    }

    /** Collapses internal newlines/runs of whitespace — each tool must stay one bullet. */
    private fun String.oneLine(): String = replace(Regex("\\s+"), " ").trim()

    private fun truncateAtWord(text: String, max: Int = MAX_DESCRIPTION_CHARS): String {
        if (text.length <= max) return text
        val cut = text.take(max)
        // Back up to the last space so we never end mid-word; if there is none, keep the cut.
        return cut.substringBeforeLast(' ', cut).trimEnd() + "…"
    }
}
