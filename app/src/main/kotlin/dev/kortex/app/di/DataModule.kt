package dev.kortex.app.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.kortex.app.data.local.AppDatabase
import dev.kortex.app.data.local.RoomChatSessionRepository
import dev.kortex.app.domain.chat.ChatSessionRepository
import dev.kortex.app.data.auth.GmailAuthManager
import dev.kortex.app.data.ai.AiGatewayClient
import dev.kortex.app.data.finance.JevFinanceDecider
import dev.kortex.app.data.finance.LlmFinanceReader
import dev.kortex.app.data.links.JevTagSuggester
import dev.kortex.app.data.settings.SettingsStore
import dev.kortex.app.data.sync.BackgroundPush
import dev.kortex.app.data.topics.GmailEmailDirectory
import dev.kortex.app.data.topics.LinksLinkCatalog
import dev.kortex.app.data.topics.LlmTopicSummarizer
import dev.kortex.core.llm.LlmProvider
import dev.kortex.core.observability.AgentRunStore
import dev.kortex.core.observability.RoomAgentRunStore
import dev.kortex.core.store.KortexDatabase
import dev.kortex.finance.domain.port.BackgroundSync
import dev.kortex.finance.domain.read.FinanceDecider
import dev.kortex.finance.domain.read.FinanceReader
import dev.kortex.links.domain.port.PageReader
import dev.kortex.links.domain.port.TagSuggester
import dev.kortex.links.domain.repository.LinksRepository
import dev.kortex.links.tagging.EmbeddingTagSuggester
import dev.kortex.myinfo.topics.domain.port.EmailDirectory
import dev.kortex.myinfo.topics.domain.port.LinkCatalog
import dev.kortex.myinfo.topics.domain.port.TopicSummarizer
import javax.inject.Singleton

/** Room databases and the stores built on them. */
@Module
@InstallIn(SingletonComponent::class)
object DataModule {

    @Provides
    @Singleton
    fun provideKortexDatabase(@ApplicationContext context: Context): KortexDatabase =
        Room.databaseBuilder(context, KortexDatabase::class.java, "kortex.db").build()

    /** Durable store of agent-run traces for the Runs inspection screen. */
    @Provides
    @Singleton
    fun provideAgentRunStore(database: KortexDatabase): AgentRunStore = RoomAgentRunStore(database.runTraceDao())

    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "app.db").build()

    /** Saved chat conversations, for the chat screen and the background share runner alike. */
    @Provides
    @Singleton
    fun provideChatSessionRepository(database: AppDatabase): ChatSessionRepository =
        RoomChatSessionRepository(database.chatSessionDao())

    /** Link tags come from Jev on Vercel AI Gateway; the on-device embeddings stand in offline. */
    @Provides
    @Singleton
    fun provideTagSuggester(gateway: AiGatewayClient, links: LinksRepository, embeddings: EmbeddingTagSuggester): TagSuggester =
        JevTagSuggester(gateway, links, embeddings)

    /** Topics keep links in the Links library rather than their own copy.*/
    @Provides
    @Singleton
    fun provideLinkCatalog(links: LinksRepository, pages: PageReader, tagSuggester: TagSuggester): LinkCatalog =
        LinksLinkCatalog(links, pages, tagSuggester)

    /** Topics read the mailbox connected in Settings, with the same read-only token the agent uses. */
    @Provides
    @Singleton
    fun provideEmailDirectory(@ApplicationContext context: Context, settings: SettingsStore): EmailDirectory =
        GmailEmailDirectory(settings, GmailAuthManager(context))

    /** Topic summaries come from the model the user picked in Settings. */
    @Provides
    @Singleton
    fun provideTopicSummarizer(llm: LlmProvider, settings: SettingsStore): TopicSummarizer =
        LlmTopicSummarizer(llm, settings)

    /** Finance's fallback for SMS and receipts the patterns can't read, on the model picked in Settings. */
    @Provides
    @Singleton
    fun provideFinanceReader(llm: LlmProvider, settings: SettingsStore): FinanceReader = LlmFinanceReader(llm, settings)

    /** Finance's quick pick-one questions (SMS kind, category) go to Jev on Vercel AI Gateway. */
    @Provides
    @Singleton
    fun provideFinanceDecider(gateway: AiGatewayClient): FinanceDecider = JevFinanceDecider(gateway)

    /** Entries added from bank SMS while the app is closed reach the cloud through a background push. */
    @Provides
    fun provideBackgroundSync(@ApplicationContext context: Context): BackgroundSync = BackgroundSync { BackgroundPush.enqueue(context) }
}
