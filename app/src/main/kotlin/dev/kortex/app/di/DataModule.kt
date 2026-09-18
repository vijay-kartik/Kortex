package dev.kortex.app.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.kortex.app.data.local.AppDatabase
import dev.kortex.app.data.local.ChatSessionDao
import dev.kortex.app.data.settings.SettingsStore
import dev.kortex.app.data.topics.LinksLinkCatalog
import dev.kortex.app.data.topics.LlmTopicSummarizer
import dev.kortex.core.llm.LlmProvider
import dev.kortex.core.observability.AgentRunStore
import dev.kortex.core.observability.RoomAgentRunStore
import dev.kortex.core.store.KortexDatabase
import dev.kortex.links.data.LinksRepository
import dev.kortex.links.tagging.PageMetadataFetcher
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

    @Provides
    fun provideChatSessionDao(database: AppDatabase): ChatSessionDao = database.chatSessionDao()

    /** Topics keep links in the Links library rather than their own copy. */
    @Provides
    @Singleton
    fun provideLinkCatalog(links: LinksRepository, pages: PageMetadataFetcher): LinkCatalog = LinksLinkCatalog(links, pages)

    /** Topic summaries come from the model the user picked in Settings. */
    @Provides
    @Singleton
    fun provideTopicSummarizer(llm: LlmProvider, settings: SettingsStore): TopicSummarizer =
        LlmTopicSummarizer(llm, settings)
}
