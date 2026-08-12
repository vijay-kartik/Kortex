package dev.kortex.core.llm

import dev.kortex.core.log.Logger
import dev.kortex.core.state.Message
import dev.kortex.core.tool.Tool
import kotlinx.coroutines.flow.Flow

/**
 * Provider-agnostic LLM interface. Default impl is [OpenAiProvider]
 */
interface LlmProvider {
    /** Providers opt in once they deliver incremental chunks rather than a terminal value. */
    val supportsStreaming: Boolean get() = false

    /**
     * [logger] overrides the provider's own logger for this one call. Graph nodes pass
     * `ctx.logger` here so per-turn observers (e.g. the chat UI's reasoning panel and its
     * token stats) see request/response logs; callers that omit it (the ambient pipeline)
     * get the provider's constructor-configured logger.
     */
    suspend fun complete(req: LlmRequest, logger: Logger? = null): LlmResponse
    fun stream(req: LlmRequest): Flow<LlmChunk>

    /**
     * Streaming counterpart which can use a per-turn logger. Keeping the one-argument
     * method above lets lightweight providers and existing test doubles stay minimal.
     */
    fun stream(req: LlmRequest, logger: Logger?): Flow<LlmChunk> = stream(req)
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
    data class ToolCallDelta(
        val index: Int,
        val id: String? = null,
        val name: String? = null,
        val argsDelta: String = "",
    ) : LlmChunk
    data object Done : LlmChunk
}

/** Catalog of model ids we route between. */
object Models {
    var REASONING = "gpt-4o"
    var FAST = "gpt-4o-mini"
    
    val supportedOpenAi = listOf(
        "gpt-4o",
        "gpt-4o-mini",
        "gpt-4-turbo",
        "gpt-4",
        "gpt-3.5-turbo",
        "o1-preview",
        "o1-mini"
    )

    /**
     * Curated Ollama Cloud models (ollama.com/search?c=cloud) as of July 2026. The catalog
     * changes often, so the settings UI also lets the user type any model id. Vision-capable
     * models (image attachments work) are listed first.
     */
    val supportedOllamaCloud = listOf(
        // Vision / multimodal
        "qwen3.5:122b",
        "qwen3.5:27b",
        "gemma4:31b",
        "kimi-k2.7-code",
        "minimax-m3",
        // Text-only
        "deepseek-v4-pro",
        "deepseek-v4-flash",
        "gpt-oss:120b",
        "gpt-oss:20b",
        "qwen3-coder:480b",
        "glm-5.2",
    )
}
