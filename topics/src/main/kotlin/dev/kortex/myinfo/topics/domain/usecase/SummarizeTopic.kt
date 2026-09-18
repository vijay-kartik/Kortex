package dev.kortex.myinfo.topics.domain.usecase

import dev.kortex.myinfo.topics.domain.model.SummaryDigest
import dev.kortex.myinfo.topics.domain.model.TopicDetail
import dev.kortex.myinfo.topics.domain.model.TopicSummary
import dev.kortex.myinfo.topics.domain.port.Clock
import dev.kortex.myinfo.topics.domain.port.TopicSummarizer
import dev.kortex.myinfo.topics.domain.repository.TopicsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow

/** The topic's cached summary (Figma: Topics 1b); compare its fingerprint to see if it is current. */
class ObserveTopicSummary(private val repository: TopicsRepository) {
    operator fun invoke(topicId: Long): Flow<TopicSummary?> = repository.observeSummary(topicId)
}

/**
 * Has the agent read [TopicDetail] and keeps what it wrote, stamped with the fingerprint of the
 * topic as it was read — so the moment the topic changes, the summary shows as out of date.
 */
class SummarizeTopic(
    private val repository: TopicsRepository,
    private val summarizer: TopicSummarizer,
    private val clock: Clock,
) {
    suspend operator fun invoke(detail: TopicDetail): SummarizeResult {
        val digest = SummaryDigest.of(detail)
        if (digest.empty) return SummarizeResult.NothingToSummarize
        val text = try {
            summarizer.summarize(digest).trim()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return SummarizeResult.Failed(e.message?.takeIf { it.isNotBlank() } ?: "The agent couldn't be reached.")
        }
        if (text.isEmpty()) return SummarizeResult.Failed("The agent came back with nothing.")
        val summary = TopicSummary(detail.topic.id, text, clock.nowMillis(), digest.fingerprint)
        repository.saveSummary(summary)
        return SummarizeResult.Saved(summary)
    }
}

sealed interface SummarizeResult {
    data class Saved(val summary: TopicSummary) : SummarizeResult

    /** An empty topic has nothing to say about it; no model is called. */
    data object NothingToSummarize : SummarizeResult

    /** The model failed or had nothing to say; [message] is fit to show. */
    data class Failed(val message: String) : SummarizeResult
}
