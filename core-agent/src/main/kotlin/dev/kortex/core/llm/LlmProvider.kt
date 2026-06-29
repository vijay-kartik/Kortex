package dev.kortex.core.llm

import dev.kortex.core.state.Message
import dev.kortex.core.tool.Tool
import kotlinx.coroutines.flow.Flow

/**
 * Provider-agnostic LLM interface. Default impl targets Anthropic Claude
 * (claude-opus-4-8 for hard reasoning, claude-haiku-4-5 for cheap routing — pattern 16),
 * but OpenAI / Gemini / on-device (Gemini Nano) can implement the same contract.
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

/** Catalog of model ids we route between. */
object Models {
    const val REASONING = "claude-opus-4-8"
    const val FAST = "claude-haiku-4-5-20251001"
}
