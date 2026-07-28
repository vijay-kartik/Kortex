package dev.kortex.core.llm

/**
 * Provider-agnostic interface for generating vector embeddings.
 */
interface EmbeddingProvider {
    /**
     * Generates an embedding for the given text.
     * @param text The text to embed.
     * @return A FloatArray representing the vector embedding.
     */
    suspend fun embed(text: String): FloatArray

    /**
     * Releases any resources held by the provider. Default is a no-op for
     * stateless (e.g. network-backed) providers; on-device providers that hold
     * native handles (interpreter, tokenizer) override this.
     */
    fun close() {}
}
