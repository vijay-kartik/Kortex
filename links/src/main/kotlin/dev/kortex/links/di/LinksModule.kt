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
import dev.kortex.links.data.LinkSyncDao
import dev.kortex.links.data.LinksDatabase
import dev.kortex.links.data.RoomLinksRepository
import dev.kortex.links.data.TagDao
import dev.kortex.links.domain.port.Clock
import dev.kortex.links.domain.port.ImageDownloads
import dev.kortex.links.domain.port.PageReader
import dev.kortex.links.domain.port.TagSuggester
import dev.kortex.links.domain.repository.LinksRepository
import dev.kortex.links.domain.usecase.AnalyzeLink
import dev.kortex.links.domain.usecase.CreateTag
import dev.kortex.links.domain.usecase.DeleteLink
import dev.kortex.links.domain.usecase.ObserveDuplicate
import dev.kortex.links.domain.usecase.ObserveLinks
import dev.kortex.links.domain.usecase.ObserveTagCounts
import dev.kortex.links.domain.usecase.ObserveTagNames
import dev.kortex.links.domain.usecase.RetryLinkImage
import dev.kortex.links.domain.usecase.SaveLink
import dev.kortex.links.domain.usecase.SetLinkTags
import dev.kortex.links.images.LinkImageStore
import dev.kortex.links.tagging.EmbeddingTagSuggester
import dev.kortex.links.tagging.LinkEmbedder
import dev.kortex.links.tagging.MediaPipeLinkEmbedder
import dev.kortex.links.tagging.PageMetadataFetcher
import javax.inject.Singleton

/** Links storage, the ports behind them, and the use cases. Domain classes carry no DI annotations; they are built here. */
@Module
@InstallIn(SingletonComponent::class)
abstract class LinksModule {

    @Binds
    abstract fun bindLinkEmbedder(impl: MediaPipeLinkEmbedder): LinkEmbedder

    @Binds
    abstract fun bindTagSuggester(impl: EmbeddingTagSuggester): TagSuggester

    @Binds
    abstract fun bindPageReader(impl: PageMetadataFetcher): PageReader

    @Binds
    abstract fun bindImageDownloads(impl: LinkImageStore): ImageDownloads

    companion object {
        @Provides
        @Singleton
        fun provideDatabase(@ApplicationContext context: Context): LinksDatabase =
            Room.databaseBuilder(context, LinksDatabase::class.java, "links.db")
                .addMigrations(LinksDatabase.MIGRATION_1_2, LinksDatabase.MIGRATION_2_3, LinksDatabase.MIGRATION_3_4)
                .addCallback(LinksDatabase.SYNC_ON_CREATE)
                .build()

        @Provides
        fun provideLinkDao(database: LinksDatabase): LinkDao = database.linkDao()

        @Provides
        fun provideLinkSyncDao(database: LinksDatabase): LinkSyncDao = database.linkSyncDao()

        @Provides
        fun provideTagDao(database: LinksDatabase): TagDao = database.tagDao()

        @Provides
        @Singleton
        fun provideRepository(linkDao: LinkDao, tagDao: TagDao, imageStore: LinkImageStore): LinksRepository =
            RoomLinksRepository(linkDao, tagDao, imageStore)

        @Provides
        fun provideClock(): Clock = Clock.System

        @Provides
        fun provideObserveLinks(repository: LinksRepository) = ObserveLinks(repository)

        @Provides
        fun provideObserveTagCounts(repository: LinksRepository) = ObserveTagCounts(repository)

        @Provides
        fun provideObserveTagNames(repository: LinksRepository) = ObserveTagNames(repository)

        @Provides
        fun provideDeleteLink(repository: LinksRepository) = DeleteLink(repository)

        @Provides
        fun provideSetLinkTags(repository: LinksRepository) = SetLinkTags(repository)

        @Provides
        fun provideCreateTag(repository: LinksRepository) = CreateTag(repository)

        @Provides
        fun provideSaveLink(repository: LinksRepository, clock: Clock) = SaveLink(repository, clock)

        @Provides
        fun provideObserveDuplicate(repository: LinksRepository) = ObserveDuplicate(repository)

        @Provides
        fun provideAnalyzeLink(pages: PageReader, suggester: TagSuggester, images: ImageDownloads) =
            AnalyzeLink(pages, suggester, images)

        @Provides
        fun provideRetryLinkImage(images: ImageDownloads) = RetryLinkImage(images)
    }
}
