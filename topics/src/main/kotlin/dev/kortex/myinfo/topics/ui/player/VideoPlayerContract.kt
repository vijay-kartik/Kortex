package dev.kortex.myinfo.topics.ui.player

import dev.kortex.myinfo.topics.domain.model.PlaybackState
import dev.kortex.myinfo.topics.domain.model.TopicItem

/**
 * A topic's video playing in the app (Figma: Topic videos 2e–2i). The embed is YouTube's own
 * player; everything Kortex shows sits above or below it, never over it.
 */
data class VideoPlayerState(
    val itemId: Long,
    val topicName: String = "",
    /** Null until the topic is read. */
    val video: TopicItem.Video? = null,
    /** The topic's other unwatched videos, newest first. */
    val upNext: List<TopicItem.Video> = emptyList(),
    /** What the player loads. Set once, when the video first arrives, so later saves don't restart it. */
    val start: PlayerStart? = null,
    /** Why the player isn't showing; null while it is. */
    val problem: PlayerProblem? = null,
    /** Counts up on Try again, so the player is built afresh. */
    val attempt: Int = 0,
    val ended: Boolean = false,
    /** The video is playing right now; the screen stays on meanwhile. */
    val playing: Boolean = false,
    /** "Resuming at m:ss · START OVER" under the player, for a few seconds after opening. */
    val resumingAtSeconds: Int? = null,
    /** Where playback is now, to the second; the progress card counts time left from it. */
    val positionSeconds: Int? = null,
    val nowMillis: Long = 0,
) {
    val showsPlayer: Boolean = start != null && problem == null

    /** Offered when the video ends; nothing plays on its own (Figma: Topic videos 2g). */
    val nextUnwatched: TopicItem.Video? = if (ended) upNext.firstOrNull() else null
}

data class PlayerStart(val videoId: String, val startSeconds: Int)

sealed interface PlayerProblem {
    /** No connection: videos stream, so there's nothing to play (Figma: Topic videos 2i). */
    data object Offline : PlayerProblem

    /** The uploader turned off playback outside YouTube (Figma: Topic videos 2h). */
    data object EmbedBlocked : PlayerProblem

    /** Removed, private, or an address with no video id in it. */
    data object Unavailable : PlayerProblem

    /** The player failed some other way; worth another go. */
    data object Failed : PlayerProblem
}

/** Player errors, as far as the screen tells them apart. */
enum class PlayerFailure { EmbedBlocked, NotFound, Other }

sealed interface VideoPlayerIntent {
    // What the embedded player reports.
    data class StateChanged(val state: PlaybackState) : VideoPlayerIntent
    data class SecondChanged(val second: Float) : VideoPlayerIntent
    data class LengthKnown(val seconds: Float) : VideoPlayerIntent
    data class RateChanged(val rate: Float) : VideoPlayerIntent
    data class Failed(val failure: PlayerFailure) : VideoPlayerIntent

    /** The app went to the background: save now, in case it isn't coming back. */
    data object Backgrounded : VideoPlayerIntent

    data object StartOver : VideoPlayerIntent
    data object TryAgain : VideoPlayerIntent

    /** Mark watched, or untick it. */
    data object ToggleWatched : VideoPlayerIntent

    /** Take back "marked watched on its own". */
    data object UndoWatched : VideoPlayerIntent
    data object OpenInYouTube : VideoPlayerIntent
    data object Share : VideoPlayerIntent

    /** An Up next row or the Next unwatched card. */
    data class Play(val video: TopicItem.Video) : VideoPlayerIntent
}

sealed interface VideoPlayerEffect {
    data class SeekTo(val seconds: Float) : VideoPlayerEffect
    data class OpenUrl(val url: String) : VideoPlayerEffect
    data class ShareText(val subject: String, val text: String) : VideoPlayerEffect

    /** "Marked watched · UNDO": it ended, or most of it has now been seen. */
    data object MarkedWatched : VideoPlayerEffect
    data class ShowMessage(val text: String) : VideoPlayerEffect

    /** Another of the topic's videos, for the topic to open as it opens any tapped item. */
    data class Play(val video: TopicItem.Video) : VideoPlayerEffect

    /** The video is gone: deleted, or moved to another topic. */
    data object Close : VideoPlayerEffect
}
