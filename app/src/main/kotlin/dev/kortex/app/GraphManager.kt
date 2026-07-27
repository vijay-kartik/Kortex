package dev.kortex.app

import android.content.Context
import dev.kortex.graph_storage.GraphBuilder
import dev.kortex.graph_storage.GraphRepository
import dev.kortex.graph_storage.GraphStorageConfig
import dev.kortex.graph_storage.MyObjectBox
import dev.kortex.graph_storage.PredicateVocabulary
import io.objectbox.BoxStore

/**
 * Encapsulates the complete Knowledge Graph storage stack for a specific database name.
 */
class GraphEnvironment(
    val name: String,
    val boxStore: BoxStore,
) {
    val graphRepository by lazy { GraphRepository(boxStore) }
    val graphBuilder by lazy { GraphBuilder(graphRepository, boxStore) }
    val predicateVocabulary by lazy { PredicateVocabulary(boxStore) }
}

/**
 * Manages multiple isolated Knowledge Graph environments.
 * By default, provides the main/global memory used by the chat agent,
 * but can spin up new isolated BoxStores for future distinct modules.
 */
class GraphManager(private val context: Context) {
    private val environments = mutableMapOf<String, GraphEnvironment>()
    
    @Synchronized
    fun getEnvironment(name: String = GraphStorageConfig.STORE_NAME): GraphEnvironment {
        return environments.getOrPut(name) {
            val boxStore = MyObjectBox.builder()
                .androidContext(context)
                .name(name)
                .build()
            GraphEnvironment(name, boxStore)
        }
    }
    
    val defaultEnvironment: GraphEnvironment
        get() = getEnvironment()
}
