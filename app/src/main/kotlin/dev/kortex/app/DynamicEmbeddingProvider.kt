package dev.kortex.app

import dev.kortex.core.llm.EmbeddingProvider
import dev.kortex.core.llm.OpenAiEmbeddingProvider
import kotlinx.coroutines.flow.first

class DynamicEmbeddingProvider(
    private val store: McpStore,
    private val defaultProvider: EmbeddingProvider,
) : EmbeddingProvider {
    private var ollamaProvider: EmbeddingProvider? = null
    private var currentOllamaUrl: String? = null
    private var currentOllamaToken: String? = null
    private var ollamaCloudProvider: EmbeddingProvider? = null
    private var currentOllamaCloudKey: String? = null
    private var openAiProvider: EmbeddingProvider? = null
    private var currentOpenAiKey: String? = null

    private suspend fun getActiveProvider(): EmbeddingProvider = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val providerType = store.activeEmbeddingProvider.first()
        val modelName = store.activeEmbeddingModel.first()

        if (providerType == "openai") {
            val key = store.openaiApiKey.first()?.trim()?.takeIf { it.isNotBlank() }
            if (key != null) {
                if (openAiProvider == null || currentOpenAiKey != key) {
                    currentOpenAiKey = key
                    openAiProvider = OpenAiEmbeddingProvider(
                        apiKey = key,
                        model = modelName,
                        logger = AndroidLogger
                    )
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
                ollamaProvider = dev.kortex.core.llm.OllamaEmbeddingProvider(
                    baseUrl = url,
                    model = modelName,
                    logger = AndroidLogger
                )
            }
            return@withContext ollamaProvider!!
        }
        if (providerType == "ollama-cloud") {
            val key = store.ollamaCloudApiKey.first()?.trim()?.takeIf { it.isNotBlank() }
            if (key != null) {
                if (ollamaCloudProvider == null || currentOllamaCloudKey != key) {
                    currentOllamaCloudKey = key
                    ollamaCloudProvider = dev.kortex.core.llm.OllamaEmbeddingProvider(
                        baseUrl = DynamicLlmProvider.OLLAMA_CLOUD_BASE_URL,
                        model = modelName,
                        logger = AndroidLogger
                    )
                }
                return@withContext ollamaCloudProvider!!
            }
            error("Ollama Cloud is selected but no API key is set.")
        }
        defaultProvider
    }

    override suspend fun embed(text: String): FloatArray {
        return getActiveProvider().embed(text)
    }
}
