package dev.kortex.app.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.kortex.app.BuildConfig
import dev.kortex.app.data.auth.McpOAuthManager
import dev.kortex.app.data.settings.McpServerStore
import dev.kortex.app.data.settings.SettingsStore
import dev.kortex.app.data.settings.asLlmProviderSettings
import dev.kortex.app.domain.agent.financeTools
import dev.kortex.app.domain.agent.linksTools
import dev.kortex.app.domain.agent.AgentBootstrap
import dev.kortex.app.domain.agent.McpConnections
import dev.kortex.app.domain.chat.ChatSessionRepository
import dev.kortex.app.domain.gmail.GmailAccess
import dev.kortex.app.domain.gmail.GmailToken
import dev.kortex.app.domain.share.ShareAgentRunner
import dev.kortex.core.gmail.gmailTool
import dev.kortex.core.llm.DynamicLlmProvider
import dev.kortex.core.llm.EmbeddingGemmaProvider
import dev.kortex.core.llm.EmbeddingProvider
import dev.kortex.core.llm.LlmProvider
import dev.kortex.core.llm.OpenAiProvider
import dev.kortex.core.llm.StubLlmProvider
import dev.kortex.core.log.AndroidLogger
import dev.kortex.core.mcp.McpToolConnector
import dev.kortex.core.observability.AgentRunStore
import dev.kortex.core.tool.ToolRegistry
import dev.kortex.core.tool.android.calendarEventTool
import dev.kortex.core.tool.android.reminderTool
import dev.kortex.core.tool.builtin.defaultTools
import dev.kortex.graph_storage.GraphBuilder
import dev.kortex.graph_storage.GraphRepository
import dev.kortex.graph_storage.PredicateVocabulary
import dev.kortex.graph_tools.KnowledgeExtractionTool
import dev.kortex.graph_tools.MemoryTool
import dev.kortex.graph_tools.SaveItineraryTool
import dev.kortex.finance.agent.FinanceAgent
import dev.kortex.links.domain.agent.LinksAgent
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope

/** The agent's LLM, embeddings, shared tool registry and background share runner. */
@Module
@InstallIn(SingletonComponent::class)
object AgentModule {

    // LLM provider — OpenAI when a key is configured, else the stub.
    @Provides
    @Singleton
    fun provideLlmProvider(settingsStore: SettingsStore): LlmProvider {
        val defaultProvider = BuildConfig.OPENAI_API_KEY.takeIf { it.isNotBlank() }
            ?.let { OpenAiProvider(apiKey = it, logger = AndroidLogger) }
            ?: StubLlmProvider()
        return DynamicLlmProvider(
            settings = settingsStore.asLlmProviderSettings(),
            defaultProvider = defaultProvider,
            logger = AndroidLogger,
        )
    }

    // Embedding provider — on-device EmbeddingGemma is the single, default provider
    // for all embeddings (384-dim Matryoshka, matching GraphStorageConfig.EMBEDDING_DIMENSIONS).
    @Provides
    @Singleton
    fun provideEmbeddingProvider(): EmbeddingProvider = EmbeddingGemmaProvider()

    // Shared tool registry — one instance for ChatViewModel + SettingsViewModel.
    @Provides
    @Singleton
    fun provideToolRegistry(
        @ApplicationContext context: Context,
        graphRepository: GraphRepository,
        graphBuilder: GraphBuilder,
        predicateVocabulary: PredicateVocabulary,
        embedder: EmbeddingProvider,
        gmail: GmailAccess,
        finance: FinanceAgent,
        links: LinksAgent,
    ): ToolRegistry = ToolRegistry(
        defaultTools() +
            MemoryTool(graphRepository, graphBuilder, embedder) +
            KnowledgeExtractionTool(graphBuilder, embedder, predicateVocabulary) +
            SaveItineraryTool() +
            financeTools(finance) +
            linksTools(links) +
            reminderTool(context) +
            calendarEventTool(context) +
            gmailTool(
                context = context,
                tokenProvider = { (gmail.token() as? GmailToken.Granted)?.token },
                invalidateToken = gmail::invalidate,
            ),
    )

    /** The one owner of MCP connections, shared by AgentBootstrap and Settings. */
    @Provides
    @Singleton
    fun provideMcpConnections(
        tools: ToolRegistry,
        settingsStore: SettingsStore,
        mcpOAuthManager: McpOAuthManager,
    ): McpConnections {
        val connector = McpToolConnector(tools, AndroidLogger)
        return McpConnections(
            tools = tools,
            repository = McpServerStore(settingsStore, mcpOAuthManager),
            connectServer = { connector.connect(it) },
            logger = AndroidLogger,
        )
    }

    /** App-wide tool/MCP/model setup; started from KortexApp so it doesn't depend on any screen. */
    @Provides
    @Singleton
    fun provideAgentBootstrap(
        @ApplicationScope scope: CoroutineScope,
        tools: ToolRegistry,
        settingsStore: SettingsStore,
        mcpConnections: McpConnections,
    ): AgentBootstrap = AgentBootstrap(
        scope = scope,
        tools = tools,
        settingsStore = settingsStore,
        mcpConnections = mcpConnections,
    )

    /** Background agent runs for files shared into Kortex from other apps. */
    @Provides
    @Singleton
    fun provideShareAgentRunner(
        @ApplicationContext context: Context,
        @ApplicationScope scope: CoroutineScope,
        llm: LlmProvider,
        tools: ToolRegistry,
        sessions: ChatSessionRepository,
        runStore: AgentRunStore,
    ): ShareAgentRunner = ShareAgentRunner(
        appContext = context,
        scope = scope,
        llm = llm,
        tools = tools,
        sessions = sessions,
        runStore = runStore,
    )
}
