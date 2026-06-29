package dev.kortex.core.tool.builtin

import dev.kortex.core.tool.RiskLevel
import dev.kortex.core.tool.Tool
import dev.kortex.core.tool.ToolResult
import dev.kortex.core.tool.tool
import java.time.ZonedDateTime

/** Current device time — trivial but useful, and a clean example of a zero-arg tool. */
fun clockTool(): Tool = tool(
    name = "current_time",
    description = "Get the current local date and time (ISO-8601).",
) {
    risk(RiskLevel.LOW)
    execute { ToolResult(true, ZonedDateTime.now().toString()) }
}

/**
 * The default tool set every agent starts with. Android-specific tools (contacts,
 * calendar, location) are added on top of this in the app module (Phase 3).
 */
fun defaultTools(): List<Tool> = listOf(
    calculatorTool(),
    webSearchTool(),
    clockTool(),
)
