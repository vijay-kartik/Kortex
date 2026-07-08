package dev.kortex.app

import android.content.Context
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
    private val context: Context,
    private val store: McpStore,
    private val defaultProvider: LlmProvider,
) : LlmProvider {
    private var ollamaProvider: LlmProvider? = null
    private var mediaPipeProvider: LlmProvider? = null
    private var llamaCppProvider: LlmProvider? = null
    private var currentOllamaUrl: String? = null
    private var currentOllamaToken: String? = null
    private var currentMediaPipePath: String? = null

    private suspend fun getActiveProvider(): LlmProvider = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val providerType = store.activeProvider.first()
        if (providerType == "ollama") {
            val url = store.ollamaUrl.first()
            val token = store.ollamaToken.first() ?: "ollama"
            if (ollamaProvider == null || currentOllamaUrl != url || currentOllamaToken != token) {
                currentOllamaUrl = url
                currentOllamaToken = token
                // Ollama natively supports the OpenAI /v1/chat/completions API since early 2024
                ollamaProvider = OpenAiProvider(apiKey = token, baseUrl = url, logger = AndroidLogger)
            }
            return@withContext ollamaProvider!!
        }
        if (providerType == "mediapipe") {
            val path = store.mediaPipeModelPath.first()
            if (path.isNullOrBlank()) {
                throw IllegalStateException("No MediaPipe model downloaded or selected")
            }
            if (mediaPipeProvider == null || currentMediaPipePath != path) {
                currentMediaPipePath = path
                mediaPipeProvider = dev.kortex.core.llm.MediaPipeProvider(
                    context = context,
                    modelPath = path,
                    logger = AndroidLogger
                )
            }
            return@withContext mediaPipeProvider!!
        }
        if (providerType == "llamacpp") {
            // For now, reuse the mediapipe model path or add a dedicated one later
            val path = store.mediaPipeModelPath.first()
            if (path.isNullOrBlank()) {
                throw IllegalStateException("No .gguf model downloaded or selected")
            }
            if (llamaCppProvider == null) {
                llamaCppProvider = dev.kortex.core.llm.LlamaCppProvider(modelPath = path)
            }
            return@withContext llamaCppProvider!!
        }
        defaultProvider
    }

    override suspend fun complete(req: LlmRequest, logger: Logger?): LlmResponse {
        return getActiveProvider().complete(req, logger)
    }

    override fun stream(req: LlmRequest): Flow<LlmChunk> = flow {
        getActiveProvider().stream(req).collect { emit(it) }
    }
}
