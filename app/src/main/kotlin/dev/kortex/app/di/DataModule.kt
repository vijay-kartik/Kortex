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
import dev.kortex.core.observability.AgentRunStore
import dev.kortex.core.observability.RoomAgentRunStore
import dev.kortex.core.store.KortexDatabase
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
}
