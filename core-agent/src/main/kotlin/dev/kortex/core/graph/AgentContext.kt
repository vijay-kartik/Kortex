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
    /** Live progress hook so the UI can show what the agent is doing right now. */
    val onProgress: ProgressListener = ProgressListener {},
)

/** Returns true if the high-risk action is approved. The Android app shows a Compose sheet. */
fun interface Approver {
    suspend fun approve(toolName: String, argumentsJson: String): Boolean
}

/** A human-readable status emitted as the agent works, e.g. "Tool usage: web_search". */
fun interface ProgressListener {
    fun report(status: String)
}
