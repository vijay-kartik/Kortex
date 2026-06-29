package dev.kortex.core.graph

import dev.kortex.core.llm.LlmProvider
import dev.kortex.core.tool.ToolGovernor
import dev.kortex.core.tool.ToolRegistry

/**
 * Shared services a node may use while running. Kept out of [dev.kortex.core.state.AgentState]
 * because these are dependencies, not data that should be serialized/checkpointed.
 */
class AgentContext(
    val llm: LlmProvider,
    val tools: ToolRegistry,
    val governor: ToolGovernor,
    /** Hook for Human-in-the-Loop (pattern 13): suspends until the UI approves/denies. */
    val approver: Approver = Approver { _, _ -> true },
)

/** Returns true if the high-risk action is approved. The Android app shows a Compose sheet. */
fun interface Approver {
    suspend fun approve(toolName: String, argumentsJson: String): Boolean
}
