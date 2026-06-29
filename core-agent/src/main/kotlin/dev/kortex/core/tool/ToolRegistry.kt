package dev.kortex.core.tool

/** Holds available tools and supports per-agent allow-lists. */
class ToolRegistry(tools: List<Tool> = emptyList()) {
    private val byName = tools.associateBy { it.name }.toMutableMap()

    fun register(tool: Tool) { byName[tool.name] = tool }
    fun get(name: String): Tool? = byName[name]
    fun all(): List<Tool> = byName.values.toList()

    /** A view limited to an allow-list, so a sub-agent only sees the tools it should. */
    fun scoped(allowed: Set<String>): ToolRegistry =
        ToolRegistry(byName.values.filter { it.name in allowed })
}
