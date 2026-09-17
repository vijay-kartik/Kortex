package dev.kortex.graph_storage

/**
 * Storage-level configuration constants.
 */
object GraphStorageConfig {

    /** On-disk name of the single BoxStore. */
    const val STORE_NAME: String = "knowledge-graph"

    /**
     * Vector dimensionality for [EmbeddingEntity].
     *
     * MUST be decided before first release: @HnswIndex(dimensions=...) is a
     * compile-time constant, and changing it later means dropping and
     * rebuilding every embedding (a re-embedding pass over all content).
     *
     * 384 fits MiniLM-class sentence encoders and EmbeddingGemma truncated
     * via Matryoshka (768 → 384 costs little quality and halves storage +
     * HNSW memory). If you commit to full-width EmbeddingGemma instead,
     * change this to 768 BEFORE any embeddings are written.
     *
     * The single-model decision stands; [EmbeddingEntity.modelVersion]
     * exists so a future model swap is a queryable migration, not a
     * guessing game.
     */
    const val EMBEDDING_DIMENSIONS: Long = 384
}
