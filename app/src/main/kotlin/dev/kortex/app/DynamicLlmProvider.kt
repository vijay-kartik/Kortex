package dev.kortex.app

import dev.kortex.core.llm.LlmChunk
import dev.kortex.core.llm.LlmProvider
import dev.kortex.core.llm.LlmRequest
import dev.kortex.core.llm.LlmResponse
import dev.kortex.core.llm.OpenAiProvider
import dev.kortex.core.log.Logger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow

class DynamicLlmProvider(
    private val store: McpStore,
    private val defaultProvider: LlmProvider,
) : LlmProvider {
    private var ollamaProvider: LlmProvider? = null
    private var currentOllamaUrl: String? = null

    private suspend fun getActiveProvider(): LlmProvider {
        val providerType = store.activeProvider.first()
        if (providerType == "ollama") {
            val url = store.ollamaUrl.first()
            if (ollamaProvider == null || currentOllamaUrl != url) {
                currentOllamaUrl = url
                // Ollama natively supports the OpenAI /v1/chat/completions API since early 2024
                ollamaProvider = OpenAiProvider(apiKey = "ollama", baseUrl = url, logger = AndroidLogger)
            }
            return ollamaProvider!!
        }
        return defaultProvider
    }

    override suspend fun complete(req: LlmRequest, logger: Logger?): LlmResponse {
        return getActiveProvider().complete(req, logger)
    }

    override fun stream(req: LlmRequest): Flow<LlmChunk> = flow {
        getActiveProvider().stream(req).collect { emit(it) }
    }
}
