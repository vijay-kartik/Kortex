package dev.kortex.core.ambient

import dev.kortex.core.store.MemoryDao
import dev.kortex.core.store.toDomain
import kotlin.math.sqrt

/**
 * Retrieval for the ambient pipeline (pattern 14: Knowledge Retrieval / RAG). It supplies
 * the `recentMemory` context that triage and the generators reason over.
 *
 * [forContact] is the working default and needs no embeddings — it returns a contact's most
 * salient stored memories. [semantic] is the vector path for when an embedder is wired: it
 * ranks stored memories by cosine similarity to a query embedding (over the BLOBs we persist).
 */
class MemoryRetriever(private val memories: MemoryDao) {

    /** Top memories for a contact by salience/recency. No embeddings required. */
    suspend fun forContact(contactId: String, limit: Int = 5): List<String> =
        memories.forContact(contactId).take(limit).map { it.content }

    /** Semantic retrieval by cosine similarity to [queryEmbedding] (optionally scoped to a contact). */
    suspend fun semantic(
        queryEmbedding: List<Float>,
        contactId: String? = null,
        limit: Int = 5,
    ): List<String> =
        memories.withEmbeddings()
            .map { it.toDomain() }
            .filter { contactId == null || it.contactId == contactId }
            .mapNotNull { mem -> mem.embedding?.let { mem to cosine(queryEmbedding, it) } }
            .sortedByDescending { it.second }
            .take(limit)
            .map { it.first.content }

    private fun cosine(a: List<Float>, b: List<Float>): Double {
        if (a.size != b.size || a.isEmpty()) return 0.0
        var dot = 0.0
        var na = 0.0
        var nb = 0.0
        for (i in a.indices) {
            dot += a[i] * b[i]
            na += a[i] * a[i]
            nb += b[i] * b[i]
        }
        val denom = sqrt(na) * sqrt(nb)
        return if (denom == 0.0) 0.0 else dot / denom
    }
}
