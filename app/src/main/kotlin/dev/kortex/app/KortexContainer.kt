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
import dev.kortex.core.llm.DeepseekProvider
import dev.kortex.core.store.KortexDatabase
import dev.kortex.core.tool.ToolRegistry
import dev.kortex.core.tool.builtin.defaultTools
import dev.kortex.app.auth.GmailAuthManager
import dev.kortex.app.auth.McpOAuthManager
import dev.kortex.app.store.AppDatabase
import dev.kortex.app.tools.gmailTool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

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

    /** Durable store of agent-run traces for the Runs inspection screen. */
    val runTraceStore: dev.kortex.core.observability.AgentRunStore by lazy {
        dev.kortex.core.observability.RoomAgentRunStore(database.runTraceDao())
    }

    val appDatabase: AppDatabase by lazy {
        Room.databaseBuilder(appContext, AppDatabase::class.java, "app.db").build()
    }
    val chatSessionDao get() = appDatabase.chatSessionDao()

    // LLM provider — OpenAI when a key is configured, else Deepseek, else the stub.
    val llm: LlmProvider by lazy {
        val defaultOpenAi = BuildConfig.OPENAI_API_KEY.takeIf { it.isNotBlank() }
            ?.let { OpenAiProvider(apiKey = it, logger = AndroidLogger) }
            ?: BuildConfig.DEEPSEEK_API_KEY.takeIf { it.isNotBlank() }
            ?.let { DeepseekProvider(apiKey = it, logger = AndroidLogger) }
            ?: StubLlmProvider()
        DynamicLlmProvider(store = mcpStore, defaultProvider = defaultOpenAi)
    }

    // Gmail OAuth2 token management (uses device's Google accounts).
    val gmailAuth: GmailAuthManager by lazy { GmailAuthManager(appContext) }

    // ObjectBox setup for Knowledge Graph
    val boxStore: io.objectbox.BoxStore by lazy {
        dev.kortex.graph_storage.MyObjectBox.builder()
            .androidContext(appContext)
            .name(dev.kortex.graph_storage.GraphStorageConfig.STORE_NAME)
            .build()
    }
    
    val graphRepository: dev.kortex.graph_storage.GraphRepository by lazy {
        dev.kortex.graph_storage.GraphRepository(boxStore)
    }

    val graphBuilder: dev.kortex.graph_storage.GraphBuilder by lazy {
        dev.kortex.graph_storage.GraphBuilder(graphRepository, boxStore)
    }

    // Embedding provider — on-device EmbeddingGemma is the single, default provider
    // for all embeddings (384-dim Matryoshka, matching GraphStorageConfig.EMBEDDING_DIMENSIONS).
    val embedder: dev.kortex.core.llm.EmbeddingProvider by lazy {
        dev.kortex.core.llm.EmbeddingGemmaProvider()
    }
    // Learned predicate vocabulary: counts the relation phrases the fixed enum
    // doesn't cover, and applies any promotions recorded against them.
    val predicateVocabulary: dev.kortex.graph_storage.PredicateVocabulary by lazy {
        dev.kortex.graph_storage.PredicateVocabulary(boxStore)
    }

    val memoryTool by lazy { dev.kortex.app.tools.MemoryTool(graphRepository, graphBuilder, embedder) }
    val knowledgeExtractionTool by lazy {
        dev.kortex.app.tools.KnowledgeExtractionTool(graphBuilder, embedder, predicateVocabulary)
    }

    // Shared tool registry — one instance for ChatViewModel + McpSettingsViewModel.
    val toolRegistry: ToolRegistry by lazy {
        ToolRegistry(
            defaultTools() + memoryTool + knowledgeExtractionTool + gmailTool(
                context = appContext,
                tokenProvider = {
                    val email = mcpStore.gmailAccountEmail.first()?.trim()
                        ?.takeIf { it.isNotBlank() } ?: return@gmailTool null
                    when (val result = gmailAuth.getToken(email)) {
                        is GmailAuthManager.AuthResult.Success -> result.token
                        else -> null
                    }
                },
            ),
        )
    }

    // MCP settings persistence (user-added servers + disabled tool names).
    val mcpStore: McpStore by lazy { McpStore(appContext) }

    val mcpOAuthManager: McpOAuthManager by lazy { 
        McpOAuthManager(
            context = appContext,
            mcpStore = mcpStore,
            appScope = appScope
        )
    }

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

    /** App-lifetime scope for work that must outlive any single activity (share intake). */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Shared state to track servers that failed with McpUnauthorizedException during startup. */
    val mcpAuthFailures = kotlinx.coroutines.flow.MutableStateFlow<Set<String>>(emptySet())

    /** Background agent runs for files shared into Kortex from other apps. */
    val shareAgentRunner: ShareAgentRunner by lazy {
        ShareAgentRunner(
            appContext = appContext,
            scope = appScope,
            llm = llm,
            tools = toolRegistry,
            sessionDao = chatSessionDao,
            runStore = runTraceStore,
        )
    }
}
