package dev.kortex.myinfo.topics.ui.player

import dev.kortex.myinfo.topics.domain.model.SeenRanges
import dev.kortex.myinfo.topics.domain.model.TopicItem
import dev.kortex.myinfo.topics.ui.common.ageLabel
import dev.kortex.myinfo.topics.ui.common.formatDuration
import dev.kortex.myinfo.topics.ui.common.spokenDuration
import kotlin.math.roundToInt

/** What the progress card under the player says and draws (Figma: Topic videos 2e–2g). */
internal data class ProgressLine(
    /** "JUST STARTED", "38% WATCHED", "✓ WATCHED · 96%". */
    val label: String,
    /** "8:42 LEFT" or "FINISHED". */
    val trailing: String,
    val watched: Boolean,
    val seen: SeenRanges,
    val lengthSeconds: Int,
    /** The resume tick on the bar: where playback is, or where it last stopped. */
    val markerSeconds: Int?,
    /** More than one stretch seen, so the bar needs its legend: skipped parts don't count. */
    val showsLegend: Boolean,
    /** For TalkBack: "38% watched, 8 minutes 42 seconds left". */
    val spoken: String,
)

/** Null until the video's length is known: without it there's nothing to measure against. */
internal fun progressLine(video: TopicItem.Video, positionSeconds: Int?): ProgressLine? {
    val length = video.durationSeconds?.takeIf { it > 0 } ?: return null
    val seen = video.progress?.seen ?: SeenRanges.Empty
    val percent = (seen.fraction(length) * 100).roundToInt()
    val position = (positionSeconds ?: video.progress?.resumeSeconds)?.coerceIn(0, length)
    val left = length - (position ?: 0)
    val finished = left <= FINISHED_WITHIN_SECONDS
    val label = when {
        video.watched -> if (percent > 0) "✓ WATCHED · $percent%" else "✓ WATCHED"
        percent == 0 && position == null -> "NOT STARTED"
        percent == 0 -> "JUST STARTED"
        else -> "$percent% WATCHED"
    }
    val spokenState = when {
        video.watched -> "Watched"
        percent == 0 -> if (position == null) "Not started" else "Just started"
        else -> "$percent% watched"
    }
    return ProgressLine(
        label = label,
        trailing = if (finished) "FINISHED" else "${formatDuration(left)} LEFT",
        watched = video.watched,
        seen = seen,
        lengthSeconds = length,
        markerSeconds = position?.takeUnless { finished },
        showsLegend = !video.watched && seen.ranges.size > 1,
        spoken = if (finished) "$spokenState, finished" else "$spokenState, ${spokenDuration(left)} left",
    )
}

/** "YOUTUBE · 14:02 · LAST WATCHED YESTERDAY", with ADDED … before it was ever played. */
internal fun videoMetaLine(video: TopicItem.Video, nowMillis: Long): String {
    val lastPlayed = video.progress?.lastPlayedAtMillis
    val activity = when {
        lastPlayed != null && video.watched -> "WATCHED ${ageLabel(lastPlayed, nowMillis)}"
        lastPlayed != null -> "LAST WATCHED ${ageLabel(lastPlayed, nowMillis)}"
        else -> "ADDED ${ageLabel(video.addedAtMillis, nowMillis)}"
    }
    return listOfNotNull("YOUTUBE", video.durationSeconds?.let(::formatDuration), activity).joinToString(" · ")
}

/** Within this of the end, there's nothing left worth counting down. */
private const val FINISHED_WITHIN_SECONDS = 1
