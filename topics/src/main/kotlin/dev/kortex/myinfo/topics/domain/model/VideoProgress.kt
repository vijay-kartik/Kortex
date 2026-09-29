package dev.kortex.myinfo.topics.domain.model

import kotlin.math.abs

/**
 * How far into a video the user got in the in-app player (docs/TOPIC_VIDEOS_PLAN.md). Null on a
 * video never played here.
 */
data class VideoProgress(
    /** Where playback last stopped; the thumbnail bar, "time left" and resuming read it. */
    val resumeSeconds: Int,
    /** What was actually seen; "N% watched" and marking it watched on its own read it. */
    val seen: SeenRanges,
    val lastPlayedAtMillis: Long?,
)

/** One stretch of a video, from [start] up to [end], in whole seconds. */
data class SeenRange(val start: Int, val end: Int) {
    init {
        require(start in 0..end) { "Bad range $start-$end" }
    }

    val seconds: Int get() = end - start
}

/**
 * The stretches of a video that were seen, sorted and merged, so rewatching a part or skipping
 * ahead can't count twice. At most [MAX_RANGES] are kept: past that, the two closest are joined,
 * which counts the small gap between them as seen.
 */
class SeenRanges private constructor(val ranges: List<SeenRange>) {

    /** Seconds seen, each counted once. */
    val coveredSeconds: Int get() = ranges.sumOf { it.seconds }

    /** Share of a video [lengthSeconds] long that was seen, 0 to 1; 0 when the length isn't known. */
    fun fraction(lengthSeconds: Int?): Float {
        if (lengthSeconds == null || lengthSeconds <= 0) return 0f
        val covered = ranges.sumOf { (it.end.coerceAtMost(lengthSeconds) - it.start).coerceAtLeast(0) }
        return (covered.toFloat() / lengthSeconds).coerceIn(0f, 1f)
    }

    operator fun plus(range: SeenRange): SeenRanges {
        if (range.seconds == 0) return this
        val merged = ArrayList<SeenRange>(ranges.size + 1)
        var current = range
        for (existing in ranges) {
            when {
                // Neighbours within a second are one stretch: ticks land about once a second.
                existing.end + JOIN_GAP < current.start -> merged += existing
                current.end + JOIN_GAP < existing.start -> { merged += current; current = existing }
                else -> current = SeenRange(minOf(existing.start, current.start), maxOf(existing.end, current.end))
            }
        }
        merged += current
        return SeenRanges(capped(merged.sortedBy { it.start }))
    }

    operator fun plus(other: SeenRanges): SeenRanges = other.ranges.fold(this) { acc, range -> acc + range }

    /** "0-190,300-320"; empty when nothing was seen. [decode] reads it back. */
    fun encode(): String = ranges.joinToString(",") { "${it.start}-${it.end}" }

    override fun equals(other: Any?) = other is SeenRanges && other.ranges == ranges
    override fun hashCode() = ranges.hashCode()
    override fun toString() = "SeenRanges(${encode()})"

    companion object {
        const val MAX_RANGES = 64
        private const val JOIN_GAP = 1

        val Empty = SeenRanges(emptyList())

        /** Reads what [encode] wrote. Pieces it can't read are skipped; null or blank is [Empty]. */
        fun decode(text: String?): SeenRanges {
            if (text.isNullOrBlank()) return Empty
            return text.split(',').fold(Empty) { seen, piece ->
                val start = piece.substringBefore('-').trim().toIntOrNull()
                val end = piece.substringAfter('-', "").trim().toIntOrNull()
                if (start == null || end == null || start < 0 || end < start) seen else seen + SeenRange(start, end)
            }
        }

        private fun capped(ranges: List<SeenRange>): List<SeenRange> {
            if (ranges.size <= MAX_RANGES) return ranges
            val joined = ranges.toMutableList()
            while (joined.size > MAX_RANGES) {
                val i = (0 until joined.size - 1).minBy { joined[it + 1].start - joined[it].end }
                joined[i] = SeenRange(joined[i].start, joined[i + 1].end)
                joined.removeAt(i + 1)
            }
            return joined
        }
    }
}

/**
 * A video's playback record as stored: what the player learns and the watched flag it may set.
 * [TopicItem.Video] carries the same, next to its link.
 */
data class VideoRecord(
    val durationSeconds: Int?,
    val watched: Boolean,
    val progress: VideoProgress?,
    /** The uploader doesn't allow playing it outside YouTube; taps go straight there. */
    val embedBlocked: Boolean,
)

/** What one stretch of playing reported, for [VideoRecord.withPlayback]. */
data class PlaybackReport(
    val resumeSeconds: Int,
    /** Stretches seen since the last report. */
    val seen: SeenRanges,
    /** Length the player gave; null when it hasn't said yet. */
    val lengthSeconds: Int?,
    /** The player reached the end. */
    val ended: Boolean,
)

/**
 * The record after [report] (docs/TOPIC_VIDEOS_PLAN.md › Rules): stretches merged, the length
 * filled in or corrected, and watched set when the video ended or its coverage just crossed
 * [WATCHED_FRACTION]. Crossing, not being past it: a video the user unticked stays unticked on
 * the next save, however much of it was seen.
 */
fun VideoRecord.withPlayback(report: PlaybackReport, nowMillis: Long): VideoRecord {
    val reported = report.lengthSeconds?.takeIf { it > 0 }
    val length = when {
        reported == null -> durationSeconds
        durationSeconds == null || abs(durationSeconds - reported) > LENGTH_TOLERANCE_SECONDS -> reported
        else -> durationSeconds
    }
    val seenBefore = progress?.seen ?: SeenRanges.Empty
    val seen = seenBefore + report.seen
    val crossed = seenBefore.fraction(length) < WATCHED_FRACTION && seen.fraction(length) >= WATCHED_FRACTION
    return copy(
        durationSeconds = length,
        watched = watched || report.ended || crossed,
        progress = VideoProgress(
            resumeSeconds = report.resumeSeconds.coerceIn(0, length ?: Int.MAX_VALUE),
            seen = seen,
            lastPlayedAtMillis = nowMillis,
        ),
    )
}

/**
 * Where to start playing: a few seconds before the resume point, so the user picks up the thread;
 * from the top when the video is watched, never played, or had [RESTART_FRACTION] or more played.
 */
fun VideoRecord.startSeconds(): Int {
    val resume = progress?.resumeSeconds ?: return 0
    if (watched) return 0
    val length = durationSeconds
    if (length != null && length > 0 && resume >= length * RESTART_FRACTION) return 0
    return (resume - RESUME_REWIND_SECONDS).coerceAtLeast(0)
}

/** Seen share at which a video counts as watched. */
const val WATCHED_FRACTION = 0.9f
private const val RESTART_FRACTION = 0.95f
private const val RESUME_REWIND_SECONDS = 3
private const val LENGTH_TOLERANCE_SECONDS = 2
