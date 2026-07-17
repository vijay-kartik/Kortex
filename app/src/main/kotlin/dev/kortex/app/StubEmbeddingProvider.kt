package dev.kortex.app

import dev.kortex.core.llm.EmbeddingProvider
import dev.kortex.graph_storage.GraphStorageConfig

/**
 * A stub provider that returns a fake deterministic vector if no real API key is configured.
 */
class StubEmbeddingProvider : EmbeddingProvider {
    override suspend fun embed(text: String): FloatArray {
        return FloatArray(GraphStorageConfig.EMBEDDING_DIMENSIONS.toInt()) { i ->
            (text.hashCode() + i).toFloat() / Int.MAX_VALUE
        }
    }
}
