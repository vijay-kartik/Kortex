package dev.kortex.core.llm

import dev.kortex.core.state.Message
import dev.kortex.core.tool.Tool
import kotlinx.coroutines.flow.Flow

/**
 * Provider-agnostic LLM interface. Default impl is [OpenAiProvider]
 * (gpt-4o for hard reasoning, gpt-4o-mini for cheap routing — pattern 16),
 * but Claude / Gemini / on-device (Gemini Nano) can implement the same contract.
 */
interface LlmProvider {
    suspend fun complete(req: LlmRequest): LlmResponse
    fun stream(req: LlmRequest): Flow<LlmChunk>
}

data class LlmRequest(
    val model: String,
    val messages: List<Message>,
    val tools: List<Tool> = emptyList(),
    val temperature: Double = 0.7,
    val maxTokens: Int = 4096,
)

data class LlmResponse(
    val message: Message,
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
) {
    val wantsTools: Boolean get() = message.toolCalls.isNotEmpty()
}

sealed interface LlmChunk {
    data class Text(val delta: String) : LlmChunk
    data class ToolCallDelta(val id: String, val name: String, val argsDelta: String) : LlmChunk
    data object Done : LlmChunk
}

/** Catalog of model ids we route between (OpenAI defaults — change in one place). */
object Models {
    const val REASONING = "gpt-4o"
    const val FAST = "gpt-4o-mini"
}
