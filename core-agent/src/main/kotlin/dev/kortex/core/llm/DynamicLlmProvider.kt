package dev.kortex.core.llm

import dev.kortex.core.log.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

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
class DynamicLlmProvider internal constructor(
    private val settings: LlmProviderSettings,
    private val defaultProvider: LlmProvider,
    private val logger: Logger,
    private val clientFactory: (apiKey: String, baseUrl: String?, supportsPdfAttachments: Boolean) -> LlmProvider,
) : LlmProvider {
    constructor(
        settings: LlmProviderSettings,
        defaultProvider: LlmProvider,
        logger: Logger = Logger.CONSOLE,
    ) : this(settings, defaultProvider, logger, { apiKey, baseUrl, supportsPdf ->
        if (baseUrl == null) {
            OpenAiProvider(apiKey = apiKey, logger = logger)
        } else {
            OpenAiProvider(
                apiKey = apiKey,
                baseUrl = baseUrl,
                logger = logger,
                supportsPdfAttachments = supportsPdf,
            )
        }
    })

    /** A client together with the settings it was built from; swapped as a whole under [lock]. */
    private class Cached(val key: List<String?>, val provider: LlmProvider)

    private val lock = Mutex()
    private var ollama: Cached? = null
    private var ollamaCloud: Cached? = null
    private var openAi: Cached? = null

    private suspend fun getActiveProvider(): LlmProvider = withContext(Dispatchers.IO) {
        val providerType = settings.activeProvider()
        if (providerType == "openai") {
            // A key entered in settings wins over the build's local.properties key,
            // which defaultProvider was built from at startup.
            val key = settings.openAiApiKey()?.trim()?.takeIf { it.isNotBlank() }
            if (key != null) {
                return@withContext lock.withLock {
                    val cacheKey = listOf(key)
                    openAi?.takeIf { it.key == cacheKey }?.provider
                        ?: Cached(cacheKey, clientFactory(key, null, true)).also { openAi = it }.provider
                }
            }
        }
        if (providerType == "ollama") {
            val url = settings.ollamaUrl()
            val token = settings.ollamaToken() ?: "ollama"
            return@withContext lock.withLock {
                val cacheKey = listOf(url, token)
                // Ollama natively supports the OpenAI /v1/chat/completions API since early 2024,
                // but not the type:"file" PDF extension — see supportsPdfAttachments.
                ollama?.takeIf { it.key == cacheKey }?.provider
                    ?: Cached(cacheKey, clientFactory(token, url, false)).also { ollama = it }.provider
            }
        }
        if (providerType == "ollama-cloud") {
            val key = settings.ollamaCloudApiKey()?.trim()?.takeIf { it.isNotBlank() }
            if (key != null) {
                return@withContext lock.withLock {
                    val cacheKey = listOf(key)
                    // Ollama Cloud speaks the OpenAI chat-completions dialect at ollama.com/v1
                    // (Bearer auth; images as base64 data URLs — external image URLs and
                    // tool_choice are not supported, which matches what OpenAiProvider sends).
                    // type:"file" PDFs aren't supported either — see supportsPdfAttachments.
                    ollamaCloud?.takeIf { it.key == cacheKey }?.provider
                        ?: Cached(cacheKey, clientFactory(key, OLLAMA_CLOUD_BASE_URL, false))
                            .also { ollamaCloud = it }.provider
                }
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
