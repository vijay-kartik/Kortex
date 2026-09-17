package dev.kortex.app.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.kortex.graph_storage.GraphBuilder
import dev.kortex.graph_storage.GraphManager
import dev.kortex.graph_storage.GraphRepository
import dev.kortex.graph_storage.PredicateVocabulary
import io.objectbox.BoxStore
import javax.inject.Singleton

/**
 * Knowledge-graph storage. The bindings below [GraphManager] read its default environment,
 * which GraphManager caches, so they need no scope of their own.
 */
@Module
@InstallIn(SingletonComponent::class)
object GraphModule {

    @Provides
    @Singleton
    fun provideGraphManager(@ApplicationContext context: Context): GraphManager = GraphManager(context)

    @Provides
    fun provideBoxStore(graphManager: GraphManager): BoxStore = graphManager.defaultEnvironment.boxStore

    @Provides
    fun provideGraphRepository(graphManager: GraphManager): GraphRepository =
        graphManager.defaultEnvironment.graphRepository

    @Provides
    fun provideGraphBuilder(graphManager: GraphManager): GraphBuilder = graphManager.defaultEnvironment.graphBuilder

    // Learned predicate vocabulary: counts the relation phrases the fixed enum
    // doesn't cover, and applies any promotions recorded against them.
    @Provides
    fun providePredicateVocabulary(graphManager: GraphManager): PredicateVocabulary =
        graphManager.defaultEnvironment.predicateVocabulary
}
