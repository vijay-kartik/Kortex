package dev.kortex.graph_storage

import io.objectbox.annotation.Entity
import io.objectbox.annotation.HnswIndex
import io.objectbox.annotation.Id
import io.objectbox.annotation.Unique
import io.objectbox.annotation.VectorDistanceType

/**
 * Semantic vector for one node. Exactly one embedding per node
 * (single-model decision); updates overwrite in place.
 *
 * Separate from nodes by design (locked): embeddings can be regenerated,
 * bulk-deleted, or migrated without touching any business entity or the
 * registry.
 *
 * ## Amendments over the locked spec (agreed)
 * - [modelVersion] and [dimensions]: 8 bytes of insurance. A future model
 *   swap becomes "query modelVersion < N, re-embed" instead of guesswork,
 *   and a dimension mismatch is detectable per row instead of crashing HNSW.
 *
 * ## Cascade
 * Deleting a node deletes its embedding row (GraphRepository, Step 3).
 *
 * ## HNSW notes
 * - COSINE distance: the right default for sentence-embedding similarity.
 * - dimensions is compile-time frozen via GraphStorageConfig — see the
 *   warning there before first release.
 * - Vectors whose length != dimensions are not indexed by ObjectBox; the
 *   repository must validate length on write.
 */
@Entity
class EmbeddingEntity(

    /** ObjectBox row id. Storage-internal. */
    @Id
    var id: Long = 0,

    /** graphKey of the node this vector describes. One vector per node. */
    @Unique
    var graphKey: Long = 0,

    /**
     * Monotonically increasing version of the embedding model that produced
     * [embedding]. Bump on any model / preprocessing change.
     */
    var modelVersion: Int = 1,

    /** Actual length of [embedding], for per-row validation. */
    var dimensions: Int = GraphStorageConfig.EMBEDDING_DIMENSIONS.toInt(),
) {

    /** The vector. Written by the embedding pipeline only. */
    @HnswIndex(
        dimensions = GraphStorageConfig.EMBEDDING_DIMENSIONS,
        distanceType = VectorDistanceType.COSINE,
    )
    lateinit var embedding: FloatArray
}
