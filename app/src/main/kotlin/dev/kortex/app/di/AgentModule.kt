package dev.kortex.app.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.kortex.app.BuildConfig
import dev.kortex.app.data.auth.GmailAuthManager
import dev.kortex.app.data.local.ChatSessionDao
import dev.kortex.app.data.settings.SettingsStore
import dev.kortex.app.data.settings.asLlmProviderSettings
import dev.kortex.app.domain.share.ShareAgentRunner
import dev.kortex.core.gmail.gmailTool
import dev.kortex.core.llm.DeepseekProvider
import dev.kortex.core.llm.DynamicLlmProvider
import dev.kortex.core.llm.EmbeddingGemmaProvider
import dev.kortex.core.llm.EmbeddingProvider
import dev.kortex.core.llm.LlmProvider
import dev.kortex.core.llm.OpenAiProvider
import dev.kortex.core.llm.StubLlmProvider
import dev.kortex.core.log.AndroidLogger
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
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first

/** The agent's LLM, embeddings, shared tool registry and background share runner. */
@Module
@InstallIn(SingletonComponent::class)
object AgentModule {

    // LLM provider — OpenAI when a key is configured, else Deepseek, else the stub.
    @Provides
    @Singleton
    fun provideLlmProvider(settingsStore: SettingsStore): LlmProvider {
        val defaultProvider = BuildConfig.OPENAI_API_KEY.takeIf { it.isNotBlank() }
            ?.let { OpenAiProvider(apiKey = it, logger = AndroidLogger) }
            ?: BuildConfig.DEEPSEEK_API_KEY.takeIf { it.isNotBlank() }
            ?.let { DeepseekProvider(apiKey = it, logger = AndroidLogger) }
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
        settingsStore: SettingsStore,
        gmailAuth: GmailAuthManager,
    ): ToolRegistry = ToolRegistry(
        defaultTools() +
            MemoryTool(graphRepository, graphBuilder, embedder) +
            KnowledgeExtractionTool(graphBuilder, embedder, predicateVocabulary) +
            SaveItineraryTool() +
            reminderTool(context) +
            calendarEventTool(context) +
            gmailTool(
                context = context,
                tokenProvider = {
                    val email = settingsStore.gmailAccountEmail.first()?.trim()
                        ?.takeIf { it.isNotBlank() } ?: return@gmailTool null
                    when (val result = gmailAuth.getToken(email)) {
                        is GmailAuthManager.AuthResult.Success -> result.token
                        else -> null
                    }
                },
            ),
    )

    /** Background agent runs for files shared into Kortex from other apps. */
    @Provides
    @Singleton
    fun provideShareAgentRunner(
        @ApplicationContext context: Context,
        @ApplicationScope scope: CoroutineScope,
        llm: LlmProvider,
        tools: ToolRegistry,
        sessionDao: ChatSessionDao,
        runStore: AgentRunStore,
    ): ShareAgentRunner = ShareAgentRunner(
        appContext = context,
        scope = scope,
        llm = llm,
        tools = tools,
        sessionDao = sessionDao,
        runStore = runStore,
    )
}
