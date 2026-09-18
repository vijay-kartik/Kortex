package dev.kortex.myinfo.topics.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.kortex.myinfo.topics.data.RoomTopicsRepository
import dev.kortex.myinfo.topics.data.local.TopicDao
import dev.kortex.myinfo.topics.data.local.TopicsDatabase
import dev.kortex.myinfo.topics.domain.port.Clock
import dev.kortex.myinfo.topics.domain.port.LinkCatalog
import dev.kortex.myinfo.topics.domain.repository.TopicsRepository
import dev.kortex.myinfo.topics.domain.usecase.AcceptTopicSuggestion
import dev.kortex.myinfo.topics.domain.usecase.AddItem
import dev.kortex.myinfo.topics.domain.usecase.CaptureItem
import dev.kortex.myinfo.topics.domain.usecase.CreateTopic
import dev.kortex.myinfo.topics.domain.usecase.DeleteItems
import dev.kortex.myinfo.topics.domain.usecase.DeleteTopic
import dev.kortex.myinfo.topics.domain.usecase.DetectItemType
import dev.kortex.myinfo.topics.domain.usecase.LookUpLink
import dev.kortex.myinfo.topics.domain.usecase.MoveItems
import dev.kortex.myinfo.topics.domain.usecase.ObserveTopic
import dev.kortex.myinfo.topics.domain.usecase.ObserveTopicSuggestions
import dev.kortex.myinfo.topics.domain.usecase.ObserveTopics
import dev.kortex.myinfo.topics.domain.usecase.SetTopicPinned
import dev.kortex.myinfo.topics.domain.usecase.UpdateTopic
import javax.inject.Singleton

/**
 * Topics storage and use cases. Domain and data classes carry no DI annotations; they are built
 * here. The host app must bind [LinkCatalog].
 */
@Module
@InstallIn(SingletonComponent::class)
object TopicsModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): TopicsDatabase =
        Room.databaseBuilder(context, TopicsDatabase::class.java, "topics.db").build()

    @Provides
    fun provideTopicDao(database: TopicsDatabase): TopicDao = database.topicDao()

    @Provides
    @Singleton
    fun provideRepository(dao: TopicDao, linkCatalog: LinkCatalog): TopicsRepository =
        RoomTopicsRepository(dao, linkCatalog)

    @Provides
    fun provideClock(): Clock = Clock.System

    @Provides
    fun provideObserveTopics(repository: TopicsRepository) = ObserveTopics(repository)

    @Provides
    fun provideObserveTopic(repository: TopicsRepository) = ObserveTopic(repository)

    @Provides
    fun provideCreateTopic(repository: TopicsRepository, clock: Clock) = CreateTopic(repository, clock)

    @Provides
    fun provideUpdateTopic(repository: TopicsRepository, clock: Clock) = UpdateTopic(repository, clock)

    @Provides
    fun provideSetTopicPinned(repository: TopicsRepository) = SetTopicPinned(repository)

    @Provides
    fun provideDeleteTopic(repository: TopicsRepository) = DeleteTopic(repository)

    @Provides
    fun provideAddItem(repository: TopicsRepository, clock: Clock) = AddItem(repository, clock)

    @Provides
    fun provideMoveItems(repository: TopicsRepository, clock: Clock) = MoveItems(repository, clock)

    @Provides
    fun provideDeleteItems(repository: TopicsRepository, clock: Clock) = DeleteItems(repository, clock)

    @Provides
    fun provideDetectItemType() = DetectItemType()

    @Provides
    fun provideObserveTopicSuggestions(repository: TopicsRepository, linkCatalog: LinkCatalog, detectItemType: DetectItemType) =
        ObserveTopicSuggestions(repository, linkCatalog, detectItemType)

    @Provides
    fun provideLookUpLink(linkCatalog: LinkCatalog) = LookUpLink(linkCatalog)

    @Provides
    fun provideCaptureItem(createTopic: CreateTopic, addItem: AddItem, detectItemType: DetectItemType) =
        CaptureItem(createTopic, addItem, detectItemType)

    @Provides
    fun provideAcceptTopicSuggestion(createTopic: CreateTopic, addItem: AddItem, detectItemType: DetectItemType) =
        AcceptTopicSuggestion(createTopic, addItem, detectItemType)
}
