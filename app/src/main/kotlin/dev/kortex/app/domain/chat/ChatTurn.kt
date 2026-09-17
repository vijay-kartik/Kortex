package dev.kortex.app.domain.chat

import dev.kortex.core.log.Logger
import dev.kortex.core.state.Message
import kotlinx.serialization.Serializable

/** One line of the agent's internal reasoning (router decision, LLM call, tool call, reflection verdict). */
@Serializable
data class ReasoningLine(val level: Logger.Level, val tag: String, val message: String)

/** Totals for one turn, shown in the reasoning panel's footer even when collapsed. */
@Serializable
data class ReasoningStats(
    val tokensUsed: Int = 0,
    val toolCalls: Int = 0,
    val durationMs: Long = 0,
)

/** A rendered chat turn. [reasoning] is only populated for assistant turns that took multiple steps. */
@Serializable
data class ChatTurn(
    val message: Message,
    val reasoning: List<ReasoningLine> = emptyList(),
    val stats: ReasoningStats = ReasoningStats(),
)
