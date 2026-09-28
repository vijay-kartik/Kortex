package dev.kortex.links.domain.port

import dev.kortex.links.domain.model.PageMetadata

data class TagSuggestion(val tagName: String, val score: Float)

/** Picks which of the user's existing tags fit a page, best first. */
interface TagSuggester {
    /** Best-effort: never throws, and returns nothing when it can't suggest (e.g. the model is missing). */
    suspend fun suggest(page: PageMetadata): List<TagSuggestion>
}
