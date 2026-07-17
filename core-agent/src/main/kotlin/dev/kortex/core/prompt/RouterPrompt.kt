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
     * The `plan` label (dropped in T1.3, restored in T4.2 with a real PlanNode) is
     * described only when present in [routes], so reduced-route callers get no dead bullet.
     */
    fun build(routes: List<String>, query: String, tools: List<Tool> = emptyList()): String {
        val inventory = ToolInventory.renderCompact(tools, MAX_TOOL_DESCRIPTION_CHARS)
        return listOfNotNull(
            "Classify the user request into exactly one of: ${routes.joinToString(", ")}.",
            "- tool_task: needs tool work — one or several tool calls, possibly chained (e.g. search the web, open a result, compute, or save facts to memory).",
            ("- plan: multiple distinct sub-goals, or steps that depend on earlier results across different topics " +
                "(e.g. compare X and Y on price, reviews, and availability, then recommend one). " +
                "A single-goal chain of tool calls is tool_task, not plan.").takeIf { "plan" in routes },
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
