package dev.kortex.myinfo.topics.domain.usecase

import dev.kortex.myinfo.topics.domain.model.PlaybackReport
import dev.kortex.myinfo.topics.domain.model.withPlayback
import dev.kortex.myinfo.topics.domain.port.Clock
import dev.kortex.myinfo.topics.domain.repository.TopicsRepository

/**
 * Saves what the in-app player saw (docs/TOPIC_VIDEOS_PLAN.md › Rules): stretches merged, the
 * length filled in, and the video marked watched when it ended or its coverage just crossed 90%.
 * Undoing that is [SetItemDone] with false; the progress stays, and later saves won't tick it
 * again unless the video is played to the end.
 */
class RecordVideoProgress(
    private val repository: TopicsRepository,
    private val clock: Clock,
) {
    /** @return true when this save marked the video watched, for the "Marked watched · Undo" snackbar. */
    suspend operator fun invoke(itemId: Long, report: PlaybackReport): Boolean {
        val now = clock.nowMillis()
        val (before, after) = repository.updateVideo(itemId, now) { it.withPlayback(report, now) } ?: return false
        return !before.watched && after.watched
    }
}

/**
 * The uploader doesn't allow playing the video outside YouTube (Figma: Topic videos 2h). From then
 * on it opens in YouTube straight away, and nothing about it is tracked.
 */
class MarkEmbedBlocked(
    private val repository: TopicsRepository,
    private val clock: Clock,
) {
    suspend operator fun invoke(itemId: Long) {
        repository.updateVideo(itemId, clock.nowMillis()) { it.copy(embedBlocked = true) }
    }
}
