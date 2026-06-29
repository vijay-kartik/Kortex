package dev.kortex.core.state

import kotlinx.serialization.Serializable

/** A single conversational turn or step result that flows through the graph. */
@Serializable
data class Message(
    val role: Role,
    val content: String,
    val toolCalls: List<ToolCall> = emptyList(),
    val toolCallId: String? = null,
) {
    enum class Role { SYSTEM, USER, ASSISTANT, TOOL }
}

@Serializable
data class ToolCall(val id: String, val name: String, val argumentsJson: String)

/** The user's mission (pattern 11: Goal Setting & Monitoring). */
@Serializable
data class Goal(val description: String, val isSatisfied: Boolean = false)

/** Pattern 16: Resource-Aware Optimization — what the run is allowed to spend. */
@Serializable
data class Budget(
    val maxTokens: Int = 100_000,
    val maxToolCalls: Int = 25,
    val maxSteps: Int = 40,
    val tokensUsed: Int = 0,
    val toolCallsMade: Int = 0,
    val steps: Int = 0,
) {
    val exhausted: Boolean
        get() = tokensUsed >= maxTokens || toolCallsMade >= maxToolCalls || steps >= maxSteps
}

/** Pattern 19: Evaluation & Monitoring — one breadcrumb per node/tool/LLM call. */
@Serializable
data class TraceEvent(val node: String, val kind: String, val detail: String, val at: Long)

/**
 * The single source of truth that flows through every [dev.kortex.core.graph.Node].
 * Immutable: nodes return a copy with their additions (LangGraph-style state passing).
 */
@Serializable
data class AgentState(
    val messages: List<Message> = emptyList(),
    val goal: Goal? = null,
    val scratch: Map<String, String> = emptyMap(),
    val budget: Budget = Budget(),
    val trace: List<TraceEvent> = emptyList(),
    val done: Boolean = false,
) {
    fun withMessage(m: Message) = copy(messages = messages + m)
    fun trace(node: String, kind: String, detail: String) =
        copy(trace = trace + TraceEvent(node, kind, detail, System.currentTimeMillis()))
}
