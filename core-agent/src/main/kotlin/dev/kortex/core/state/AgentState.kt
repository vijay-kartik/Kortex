package dev.kortex.core.state

import kotlinx.serialization.Serializable

/** A file or media attachment added to a message. */
@Serializable
data class Attachment(
    val mimeType: String,
    val dataBase64: String,
    val filename: String? = null,
    /** For voice input: the on-device speech-to-text transcript. When set on an audio
     *  attachment, providers send this text to the LLM instead of raw audio bytes (which
     *  only audio-capable models accept, and which dictation doesn't produce anyway). */
    val transcript: String? = null,
    /** Length of the recording, for display (e.g. "0:07") — not sent to the LLM. */
    val durationMs: Long? = null,
)

/** A single conversational turn or step result that flows through the graph. */
@Serializable
data class Message(
    val role: Role,
    val content: String,
    val attachments: List<Attachment> = emptyList(),
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

/**
 * Pattern 6 (Planning): one step of a decomposed request. [result] is the step's outcome
 * summary — it feeds later steps' scoped contexts and the final synthesis. A FAILED step
 * never aborts the plan; later steps and the synthesizer see the failure and work around it.
 */
@Serializable
data class PlanStep(
    val description: String,
    val status: Status = Status.PENDING,
    val result: String = "",
) {
    enum class Status { PENDING, DONE, FAILED }
}

/**
 * Pattern 6 (Planning): the fixed step list PlanNode produced. Immutable like the rest of
 * the state — ExecuteStepNode copies-on-write as steps complete. Typed (not scratch-JSON)
 * because the plan is structured, mutated across nodes, and worth tracing/checkpointing
 * first-class.
 */
@Serializable
data class Plan(val steps: List<PlanStep>) {
    /** Index of the first PENDING step; null when every step is DONE or FAILED. */
    val nextPending: Int?
        get() = steps.indexOfFirst { it.status == PlanStep.Status.PENDING }.takeIf { it >= 0 }
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
    /** Pattern 6 (Planning): set by PlanNode when the request was decomposed; null on
     *  the direct/react paths and when planning degraded. Defaulted so previously
     *  serialized states still decode. */
    val plan: Plan? = null,
    val budget: Budget = Budget(),
    val trace: List<TraceEvent> = emptyList(),
    val done: Boolean = false,
) {
    fun withMessage(m: Message) = copy(messages = messages + m)
    fun trace(node: String, kind: String, detail: String) =
        copy(trace = trace + TraceEvent(node, kind, detail, System.currentTimeMillis()))
}
