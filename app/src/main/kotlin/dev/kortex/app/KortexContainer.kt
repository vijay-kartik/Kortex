package dev.kortex.app

import android.content.Context
import androidx.room.Room
import dev.kortex.core.ambient.AmbientAnalyzer
import dev.kortex.core.ambient.AmbientCoordinator
import dev.kortex.core.ambient.AmbientTriage
import dev.kortex.core.ambient.CardGuardrails
import dev.kortex.core.ambient.IdentityResolver
import dev.kortex.core.ambient.LlmCardGenerator
import dev.kortex.core.ambient.LlmMemoryWriter
import dev.kortex.core.ambient.MemoryRetriever
import dev.kortex.core.ambient.OutcomeWriter
import dev.kortex.core.ambient.SignalIngestor
import dev.kortex.core.llm.LlmProvider
import dev.kortex.core.llm.OpenAiProvider
import dev.kortex.core.store.KortexDatabase
import dev.kortex.core.tool.ToolRegistry
import dev.kortex.core.tool.builtin.defaultTools
import dev.kortex.app.store.AppDatabase

/**
 * Manual dependency container (no DI framework — fewer moving parts). Built once in
 * [KortexApp] and assembles the whole ambient pipeline from the Room store up to the
 * [AmbientCoordinator]. Everything is a lazy singleton.
 */
class KortexContainer(context: Context) {
    private val appContext = context.applicationContext

    val database: KortexDatabase by lazy {
        Room.databaseBuilder(appContext, KortexDatabase::class.java, "kortex.db").build()
    }

    // DAOs
    val contactDao get() = database.contactDao()
    val signalDao get() = database.signalDao()
    val conversationDao get() = database.conversationDao()
    val cardDao get() = database.cardDao()
    val memoryDao get() = database.memoryDao()
    val graphEntityDao get() = database.graphEntityDao()
    val mentionDao get() = database.mentionDao()
    val relationDao get() = database.relationDao()

    val appDatabase: AppDatabase by lazy {
        Room.databaseBuilder(appContext, AppDatabase::class.java, "app.db").build()
    }
    val chatSessionDao get() = appDatabase.chatSessionDao()

    // LLM provider — Wraps the default OpenAI provider with a Dynamic provider that can switch to Ollama
    val llm: LlmProvider by lazy {
        val defaultOpenAi = BuildConfig.OPENAI_API_KEY.takeIf { it.isNotBlank() }
            ?.let { OpenAiProvider(apiKey = it, logger = AndroidLogger) }
            ?: StubLlmProvider()
        DynamicLlmProvider(context = appContext, store = mcpStore, defaultProvider = defaultOpenAi)
    }

    // Shared tool registry — one instance for ChatViewModel + McpSettingsViewModel.
    val toolRegistry: ToolRegistry by lazy { ToolRegistry(defaultTools()) }

    // MCP settings persistence (user-added servers + disabled tool names).
    val mcpStore: McpStore by lazy { McpStore(appContext) }

    // Pipeline collaborators
    private val retriever by lazy { MemoryRetriever(memoryDao) }
    private val resolver by lazy { IdentityResolver(contactDao) }
    private val ingestor by lazy { SignalIngestor(resolver, signalDao, conversationDao, contactDao) }
    private val outcomeWriter by lazy {
        OutcomeWriter(cardDao, memoryDao, graphEntityDao, mentionDao, relationDao)
    }
    private val analyzer by lazy {
        AmbientAnalyzer(
            triage = AmbientTriage(llm),
            cardGenerator = LlmCardGenerator(llm),
            memoryWriter = LlmMemoryWriter(llm),
            guardrails = CardGuardrails(),
            writer = outcomeWriter,
        )
    }

    /** Top of the ambient pipeline — call onSignal (real-time) / reviewContact (periodic). */
    val coordinator: AmbientCoordinator by lazy {
        AmbientCoordinator(ingestor, analyzer, retriever, signalDao, conversationDao)
    }

    val contactSeeder by lazy { ContactSeeder(appContext, contactDao) }
}

