package dev.kortex.links.di

import android.content.Context
import androidx.room.Room
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.kortex.links.data.LinkDao
import dev.kortex.links.data.LinksDatabase
import dev.kortex.links.data.TagDao
import dev.kortex.links.tagging.EmbeddingTagSuggester
import dev.kortex.links.tagging.LinkEmbedder
import dev.kortex.links.tagging.MediaPipeLinkEmbedder
import dev.kortex.links.tagging.TagSuggester
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class LinksModule {

    @Binds
    abstract fun bindLinkEmbedder(impl: MediaPipeLinkEmbedder): LinkEmbedder

    @Binds
    abstract fun bindTagSuggester(impl: EmbeddingTagSuggester): TagSuggester

    companion object {
        @Provides
        @Singleton
        fun provideDatabase(@ApplicationContext context: Context): LinksDatabase =
            Room.databaseBuilder(context, LinksDatabase::class.java, "links.db")
                .addMigrations(LinksDatabase.MIGRATION_1_2)
                .build()

        @Provides
        fun provideLinkDao(database: LinksDatabase): LinkDao = database.linkDao()

        @Provides
        fun provideTagDao(database: LinksDatabase): TagDao = database.tagDao()
    }
}
