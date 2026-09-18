package dev.kortex.myinfo.topics.domain.port

import dev.kortex.myinfo.topics.domain.model.SummaryDigest

/**
 * Writes the agent's short read of a topic (Figma: Topics 1b). Supplied by the host app, which
 * owns the model and the user's provider settings; `:topics` never talks to a model itself.
 */
interface TopicSummarizer {
    /**
     * A few sentences on what [digest] holds and what is still open in it, as plain text.
     * Throws when the model can't be reached or refuses; the caller reports it.
     */
    suspend fun summarize(digest: SummaryDigest): String
}
