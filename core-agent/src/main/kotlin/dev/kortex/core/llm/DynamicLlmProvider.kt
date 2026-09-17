package dev.kortex.core.llm

import dev.kortex.core.log.Logger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** The user's current provider choice and credentials, read fresh on every LLM call. */
interface LlmProviderSettings {
    /** "openai", "ollama", "ollama-cloud", or anything else for [DynamicLlmProvider]'s default. */
    suspend fun activeProvider(): String
    suspend fun openAiApiKey(): String?
    suspend fun ollamaUrl(): String
    suspend fun ollamaToken(): String?
    suspend fun ollamaCloudApiKey(): String?
}

/**
 * Routes each call to the provider currently selected in [settings], rebuilding the
 * underlying client only when its URL or key changes.
 */
class DynamicLlmProvider(
    private val settings: LlmProviderSettings,
    private val defaultProvider: LlmProvider,
    private val logger: Logger = Logger.CONSOLE,
) : LlmProvider {
    private var ollamaProvider: LlmProvider? = null
    private var currentOllamaUrl: String? = null
    private var currentOllamaToken: String? = null
    private var ollamaCloudProvider: LlmProvider? = null
    private var currentOllamaCloudKey: String? = null
    private var openAiProvider: LlmProvider? = null
    private var currentOpenAiKey: String? = null

    private suspend fun getActiveProvider(): LlmProvider = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val providerType = settings.activeProvider()
        if (providerType == "openai") {
            // A key entered in settings wins over the build's local.properties key,
            // which defaultProvider was built from at startup.
            val key = settings.openAiApiKey()?.trim()?.takeIf { it.isNotBlank() }
            if (key != null) {
                if (openAiProvider == null || currentOpenAiKey != key) {
                    currentOpenAiKey = key
                    openAiProvider = OpenAiProvider(apiKey = key, logger = logger)
                }
                return@withContext openAiProvider!!
            }
        }
        if (providerType == "ollama") {
            val url = settings.ollamaUrl()
            val token = settings.ollamaToken() ?: "ollama"
            if (ollamaProvider == null || currentOllamaUrl != url || currentOllamaToken != token) {
                currentOllamaUrl = url
                currentOllamaToken = token
                // Ollama natively supports the OpenAI /v1/chat/completions API since early 2024,
                // but not the type:"file" PDF extension — see supportsPdfAttachments.
                ollamaProvider = OpenAiProvider(
                    apiKey = token,
                    baseUrl = url,
                    logger = logger,
                    supportsPdfAttachments = false,
                )
            }
            return@withContext ollamaProvider!!
        }
        if (providerType == "ollama-cloud") {
            val key = settings.ollamaCloudApiKey()?.trim()?.takeIf { it.isNotBlank() }
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
                        logger = logger,
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

    override fun stream(req: LlmRequest): Flow<LlmChunk> = flow {
        getActiveProvider().stream(req).collect { emit(it) }
    }
}
