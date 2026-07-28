package dev.kortex.core.prompt

import dev.kortex.core.tool.Tool

/**
 * Decomposition prompt for [dev.kortex.core.pattern.PlanNode] (pattern 6: Planning).
 * Kept token-lean: it runs once per plan-routed request on the REASONING model.
 *
 * The compact tool inventory grounds the steps in what tools actually exist, so the
 * planner never emits a step requiring an unavailable capability. Output contract:
 * a JSON array of step strings and nothing else — parsed by PlanNode with a
 * fence-stripping/first-bracket idiom.
 */
object PlanPrompt {

    fun build(goal: String, tools: List<Tool> = emptyList()): String {
        val inventory = ToolInventory.renderCompact(tools)
        return listOfNotNull(
            "Decompose the user's request into 2 to 5 sequential steps.",
            "Each step is ONE concrete instruction executable on its own; later steps may build on earlier steps' results.",
            inventory.takeIf { it.isNotEmpty() }?.let {
                "Available tools:\n$it\n" +
                    "Only plan steps these tools (plus plain reasoning) can execute — never a step needing an unavailable capability."
            },
            "Respond with ONLY a JSON array of step strings, e.g. [\"first step\", \"second step\"].",
            "",
            "Request: $goal",
        ).joinToString("\n")
    }
}
