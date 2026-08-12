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
    private val store: SettingsStore,
    private val defaultProvider: LlmProvider,
) : LlmProvider {
    override val supportsStreaming: Boolean = true

    private var ollamaProvider: LlmProvider? = null
    private var currentOllamaUrl: String? = null
    private var currentOllamaToken: String? = null
    private var ollamaCloudProvider: LlmProvider? = null
    private var currentOllamaCloudKey: String? = null
    private var openAiProvider: LlmProvider? = null
    private var currentOpenAiKey: String? = null

    private suspend fun getActiveProvider(): LlmProvider = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val providerType = store.activeProvider.first()
        if (providerType == "openai") {
            // A key entered in settings wins over the build's local.properties key,
            // which defaultProvider was built from at startup.
            val key = store.openaiApiKey.first()?.trim()?.takeIf { it.isNotBlank() }
            if (key != null) {
                if (openAiProvider == null || currentOpenAiKey != key) {
                    currentOpenAiKey = key
                    openAiProvider = OpenAiProvider(apiKey = key, logger = AndroidLogger)
                }
                return@withContext openAiProvider!!
            }
        }
        if (providerType == "ollama") {
            val url = store.ollamaUrl.first()
            val token = store.ollamaToken.first() ?: "ollama"
            if (ollamaProvider == null || currentOllamaUrl != url || currentOllamaToken != token) {
                currentOllamaUrl = url
                currentOllamaToken = token
                // Ollama natively supports the OpenAI /v1/chat/completions API since early 2024,
                // but not the type:"file" PDF extension — see supportsPdfAttachments.
                ollamaProvider = OpenAiProvider(
                    apiKey = token,
                    baseUrl = url,
                    logger = AndroidLogger,
                    supportsPdfAttachments = false,
                )
            }
            return@withContext ollamaProvider!!
        }
        if (providerType == "ollama-cloud") {
            val key = store.ollamaCloudApiKey.first()?.trim()?.takeIf { it.isNotBlank() }
            if (key != null) {
                if (ollamaCloudProvider == null || currentOllamaCloudKey != key) {
                    currentOllamaCloudKey = key
                    // Ollama Cloud speaks the OpenAI chat-completions dialect at ollama.com/v1
                    // (Bearer auth; images as base64 data URLs — external image URLs and
                    // tool_choice are not supported, which matches what OpenAiProvider sends).
                    // type:"file" PDFs aren't supported either — see supportsPdfAttachments.
                    ollamaCloudProvider = OpenAiProvider(
                        apiKey = key,
                        baseUrl = OLLAMA_CLOUD_BASE_URL,
                        logger = AndroidLogger,
                        supportsPdfAttachments = false,
                    )
                }
                return@withContext ollamaCloudProvider!!
            }
            // Ollama Cloud is explicitly selected but has no key — fail loudly instead of
            // silently answering via defaultProvider (which is OpenAI in most dev builds).
            error("Ollama Cloud is selected but no API key is set. Add one in Settings.")
        }
        defaultProvider
    }

    companion object {
        const val OLLAMA_CLOUD_BASE_URL = "https://ollama.com/v1"
    }

    override suspend fun complete(req: LlmRequest, logger: Logger?): LlmResponse {
        return getActiveProvider().complete(req, logger)
    }

    override fun stream(req: LlmRequest): Flow<LlmChunk> = stream(req, null)

    override fun stream(req: LlmRequest, logger: Logger?): Flow<LlmChunk> = flow {
        getActiveProvider().stream(req, logger).collect { emit(it) }
    }
}
