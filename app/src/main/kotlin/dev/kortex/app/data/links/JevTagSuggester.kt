package dev.kortex.app.data.links

import android.util.Log
import dev.kortex.app.data.ai.AiGatewayClient
import dev.kortex.app.data.ai.probability
import dev.kortex.links.domain.model.PageMetadata
import dev.kortex.links.domain.port.TagSuggester
import dev.kortex.links.domain.port.TagSuggestion
import dev.kortex.links.domain.repository.LinksRepository
import dev.kortex.links.tagging.EmbeddingTagSuggester
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Asks Jev (Vercel AI Gateway) one yes/no question per existing tag — "does this page belong
 * under it?" — and suggests the tags it's sure of, most likely first. Jev's probabilities stand
 * in for the hand-tuned cut-offs the embedding comparison needed.
 *
 * When Jev can't answer (no key, offline, slow), [fallback] — the on-device embedding
 * comparison — suggests instead, so tagging still works without a connection.
 */
class JevTagSuggester(
    private val gateway: AiGatewayClient,
    private val links: LinksRepository,
    private val fallback: EmbeddingTagSuggester,
) : TagSuggester {

    override suspend fun suggest(page: PageMetadata): List<TagSuggestion> {
        val tags = links.observeTagNames().first().filter { it.isNotBlank() }.distinct()
        if (tags.isEmpty()) return emptyList()
        return askJev(page, tags) ?: fallback.suggest(page)
    }

    /** Null when Jev couldn't answer, as opposed to an empty list when no tag fits. */
    private suspend fun askJev(page: PageMetadata, tags: List<String>): List<TagSuggestion>? {
        if (!gateway.isConfigured) return null
        return try {
            withTimeoutOrNull(TIMEOUT_MS) {
                coroutineScope {
                    tags.chunked(QUESTIONS_PER_REQUEST)
                        .map { chunk -> async { ask(page, chunk) } }
                        .awaitAll()
                        .flatten()
                }
            }
                ?.filter { it.score >= MIN_PROBABILITY }
                ?.sortedByDescending { it.score }
                ?.take(MAX_SUGGESTIONS)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Jev tag suggestion failed for ${page.url}", e)
            null
        }
    }

    private suspend fun ask(page: PageMetadata, tags: List<String>): List<TagSuggestion> {
        // Question ids are positional; tag names can hold anything.
        val questions = buildJsonObject {
            tags.forEachIndexed { i, tag ->
                putJsonObject("t$i") {
                    put("type", "boolean")
                    put("instructions", "Does this page belong under the user's tag \"$tag\"?")
                }
            }
        }
        val answers = gateway.evaluate(page.toState(), questions)
        return tags.mapIndexedNotNull { i, tag -> answers.probability("t$i")?.let { TagSuggestion(tag, it.toFloat()) } }
    }

    private fun PageMetadata.toState(): JsonObject = buildJsonObject {
        put("url", url)
        title?.let { put("title", it) }
        description?.let { put("description", it) }
        siteName?.let { put("site", it) }
        if (keywords.isNotEmpty()) put("keywords", keywords.joinToString(", "))
        bodySnippet?.let { put("excerpt", it) }
    }

    private companion object {
        const val TAG = "JevTagSuggester"

        /** The save sheet shows "Suggesting tags…" meanwhile; past this, the embeddings answer. */
        const val TIMEOUT_MS = 8_000L

        /** Keeps each request well inside Jev's 64k-token limit, however many tags there are. */
        const val QUESTIONS_PER_REQUEST = 50

        const val MIN_PROBABILITY = 0.5f
        const val MAX_SUGGESTIONS = 3
    }
}
