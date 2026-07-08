package dev.kortex.core.tool

/** Holds available tools and supports per-agent allow-lists and runtime enable/disable. */
class ToolRegistry(tools: List<Tool> = emptyList()) {
    private val byName = tools.associateBy { it.name }.toMutableMap()
    private val disabled = mutableSetOf<String>()

    fun register(tool: Tool) { byName[tool.name] = tool }
    fun unregister(name: String) { byName.remove(name); disabled.remove(name) }

    /** Returns the tool only if it exists *and* is enabled. */
    fun get(name: String): Tool? = byName[name]?.takeIf { it.name !in disabled }

    /** All enabled tools — the set the LLM sees via function calling. */
    fun all(): List<Tool> = byName.values.filter { it.name !in disabled }

    /** Every registered tool regardless of enabled state (for the settings UI). */
    fun allIncludingDisabled(): List<Tool> = byName.values.toList()

    fun disable(name: String) { disabled += name }
    fun enable(name: String) { disabled -= name }
    fun setDisabled(names: Set<String>) { disabled.clear(); disabled.addAll(names) }
    fun isDisabled(name: String): Boolean = name in disabled
    fun disabledNames(): Set<String> = disabled.toSet()

    /** A view limited to an allow-list, so a sub-agent only sees the tools it should. */
    fun scoped(allowed: Set<String>): ToolRegistry =
        ToolRegistry(byName.values.filter { it.name in allowed })
}
