package dev.kortex.links.tagging

import dev.kortex.links.data.TagDao
import dev.kortex.links.data.TagEntity
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import kotlin.math.sqrt

data class TagSuggestion(val tagName: String, val score: Float)

/** Picks which of the user's existing tags fit a page, best first. */
interface TagSuggester {
    suspend fun suggest(page: PageMetadata): List<TagSuggestion>
}

/**
 * Embeds the page once and compares it with each tag's cached vector. Needs no training:
 * a tag is suggestible as soon as it exists, since its vector is computed on first use.
 */
class EmbeddingTagSuggester @Inject constructor(
    private val embedder: LinkEmbedder,
    private val tagDao: TagDao,
) : TagSuggester {

    override suspend fun suggest(page: PageMetadata): List<TagSuggestion> {
        val tags = tagDao.getAll()
        val pageText = page.toEmbeddingText()
        if (tags.isEmpty() || pageText.isBlank()) return emptyList()

        val pageVector = embedder.embed(pageText)
        val scored = tags.map { TagSuggestion(it.name, cosine(pageVector, vectorFor(it))) }

        // An absolute floor rejects pages that match nothing; the gap to the best score keeps
        // a strong match from dragging weak ones along.
        val cutoff = maxOf(MIN_SCORE, scored.maxOf { it.score } - MAX_GAP_FROM_BEST)
        return scored
            .filter { it.score >= cutoff }
            .sortedByDescending { it.score }
            .take(MAX_SUGGESTIONS)
    }

    private suspend fun vectorFor(tag: TagEntity): FloatArray {
        val cached = tag.embedding
        if (cached != null && tag.embeddingModel == embedder.modelId) return cached.toFloatArray()
        return embedder.embed(tag.name).also { tagDao.updateEmbedding(tag.id, it.toByteArray(), embedder.modelId) }
    }

    private companion object {
        // Starting points, not calibrated for USE yet — tune against real tagged links.
        const val MIN_SCORE = 0.25f
        const val MAX_GAP_FROM_BEST = 0.10f
        const val MAX_SUGGESTIONS = 3
    }
}

private fun cosine(a: FloatArray, b: FloatArray): Float {
    var dot = 0f
    var normA = 0f
    var normB = 0f
    for (i in a.indices) {
        dot += a[i] * b[i]
        normA += a[i] * a[i]
        normB += b[i] * b[i]
    }
    return if (normA == 0f || normB == 0f) 0f else dot / (sqrt(normA) * sqrt(normB))
}

private fun FloatArray.toByteArray(): ByteArray =
    ByteBuffer.allocate(size * Float.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        .also { it.asFloatBuffer().put(this) }
        .array()

private fun ByteArray.toFloatArray(): FloatArray {
    val floats = ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
    return FloatArray(floats.remaining()).also { floats.get(it) }
}
