package dev.kortex.core.prompt

import dev.kortex.core.tool.Tool

/** Classification prompt for [dev.kortex.core.pattern.RouterNode] (pattern 2: Routing). */
object RouterPrompt {

    /**
     * Router tool bullets keep descriptions very short (T1.5): this prompt runs on the
     * FAST model for every request, so the whole inventory must add well under ~200 tokens.
     */
    internal const val MAX_TOOL_DESCRIPTION_CHARS = 60

    /**
     * [tools] is the live registry contents (`ctx.tools.all()`), rendered compactly so the
     * router chooses `tool_task` only when a registered tool plausibly helps (finding F6).
     * With no tools registered, the inventory section is omitted entirely.
     *
     * The dead `plan` label was removed in T1.3 — `tool_task` now explicitly covers
     * multi-step tool work (re-add `plan` only if/when a real PlanNode lands, T4.2).
     */
    fun build(routes: List<String>, query: String, tools: List<Tool> = emptyList()): String {
        val inventory = ToolInventory.renderCompact(tools, MAX_TOOL_DESCRIPTION_CHARS)
        return listOfNotNull(
            "Classify the user request into exactly one of: ${routes.joinToString(", ")}.",
            "- simple_qa: answerable directly with general knowledge, no tools needed.",
            "- tool_task: needs tool work — one or several tool calls, possibly chained (e.g. search the web, open a result, compute).",
            inventory.takeIf { it.isNotEmpty() }?.let {
                "Available tools:\n$it\n" +
                    "Choose tool_task only if one of these tools plausibly helps with the request; otherwise choose simple_qa."
            },
            "Respond with ONLY the label.",
            "",
            "Request: $query",
        ).joinToString("\n")
    }
}
