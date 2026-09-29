package dev.kortex.myinfo.topics.domain.model

import dev.kortex.myinfo.topics.domain.port.Clock
import kotlin.math.roundToInt

/** The player's state, as far as counting cares; the player layer maps the library's onto it. */
enum class PlaybackState { Unstarted, Buffering, Playing, Paused, Ended, Other }

/**
 * Turns what the embedded player reports into [PlaybackReport]s to save (docs/TOPIC_VIDEOS_PLAN.md
 * › Rules). One per time the player is open; no Android types, so it's tested with scripted ticks.
 *
 * While playing, each tick one small step past the last extends the open stretch. Any other jump —
 * a seek either way, an ad, a gap after buffering — closes it, and a new one starts at the new
 * second. Ads hold the video's own clock still, so their ticks add nothing.
 *
 * A report is due on pause, on end, every [FLUSH_EVERY_MILLIS] while playing, and on [close].
 */
class WatchSession(
    private val clock: Clock,
    /** Where playback starts; the resume point until the first tick. */
    startSeconds: Int = 0,
) {
    private var state = PlaybackState.Unstarted
    private var rate = 1f
    private var lengthSeconds: Float? = null
    private var lastSecond = startSeconds.toFloat()

    private var openStart: Float? = null
    private var openEnd = 0f
    private var pending = SeenRanges.Empty
    private var ended = false

    /** Resume point in the last report; a report is only due when something moved since. */
    private var reportedResume = startSeconds
    private var lastFlushMillis = clock.nowMillis()

    /** The player's position as last reported. */
    val currentSecond: Float get() = lastSecond

    fun onLength(seconds: Float) {
        if (seconds > 0f) lengthSeconds = seconds
    }

    fun onRate(rate: Float) {
        if (rate > 0f) this.rate = rate
    }

    /** @return a report to save, when this change calls for one. */
    fun onState(newState: PlaybackState): PlaybackReport? {
        val was = state
        state = newState
        if (newState == PlaybackState.Playing) {
            if (was != PlaybackState.Playing) lastFlushMillis = clock.nowMillis()
            return null
        }
        closeStretch()
        return when (newState) {
            PlaybackState.Ended -> {
                ended = true
                lengthSeconds?.let { lastSecond = it }
                flush()
            }
            PlaybackState.Paused -> flush()
            else -> null
        }
    }

    /** @return a report to save, when one is due. */
    fun onSecond(second: Float): PlaybackReport? {
        if (second < 0f) return null
        if (state == PlaybackState.Playing) {
            val start = openStart
            val step = second - openEnd
            when {
                start == null -> { openStart = second; openEnd = second }
                step == 0f -> Unit
                step > 0f && step <= MAX_STEP_SECONDS * rate -> openEnd = second
                else -> { closeStretch(); openStart = second; openEnd = second }
            }
        }
        lastSecond = second
        val due = state == PlaybackState.Playing && clock.nowMillis() - lastFlushMillis >= FLUSH_EVERY_MILLIS
        return if (due) flush() else null
    }

    /** Leaving the player or the app: whatever wasn't saved yet, or null when nothing was. */
    fun close(): PlaybackReport? {
        closeStretch()
        return flush()
    }

    private fun closeStretch() {
        val start = openStart ?: return
        val from = start.roundToInt()
        val to = openEnd.roundToInt()
        if (to > from) pending += SeenRange(from, to)
        openStart = null
    }

    /** Reports what's pending, keeping the open stretch open from where it got to. */
    private fun flush(): PlaybackReport? {
        lastFlushMillis = clock.nowMillis()
        openStart?.let {
            closeStretch()
            openStart = openEnd
        }
        val resume = lastSecond.toInt()
        if (pending == SeenRanges.Empty && !ended && resume == reportedResume) return null
        val report = PlaybackReport(
            resumeSeconds = resume,
            seen = pending,
            lengthSeconds = lengthSeconds?.roundToInt(),
            ended = ended,
        )
        pending = SeenRanges.Empty
        ended = false
        reportedResume = resume
        return report
    }

    companion object {
        /** Biggest step between ticks at 1× that still counts as playing on, not a jump. */
        const val MAX_STEP_SECONDS = 2.5f
        const val FLUSH_EVERY_MILLIS = 15_000L
    }
}
