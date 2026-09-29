package dev.kortex.myinfo.topics.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kortex.design.Ink
import dev.kortex.design.Muted
import dev.kortex.design.Sunken
import dev.kortex.design.Synapse
import dev.kortex.design.Void
import dev.kortex.myinfo.topics.domain.model.TopicItem
import dev.kortex.myinfo.topics.domain.model.startSeconds
import kotlin.math.roundToInt

/**
 * A half-watched video: how far along it stops, for the bar on its thumbnail, and what's left, for
 * "m:ss LEFT" (Figma: Topic videos 2a, 2k).
 */
internal data class VideoResume(
    /** Resume point over length, 0 to 1. */
    val fraction: Float,
    val secondsLeft: Int,
    /** Share actually seen, as the player's card counts it; for TalkBack. */
    val percentSeen: Int,
)

/**
 * Null unless opening the video would pick up partway: not watched, played here past its first
 * seconds and not nearly to the end, and its length known. One rule with the player's, so the card
 * never says "8:42 LEFT" about a video that would start over.
 */
internal val TopicItem.Video.resume: VideoResume?
    get() {
        if (embedBlocked || record.startSeconds() == 0) return null
        val length = durationSeconds?.takeIf { it > 0 } ?: return null
        val played = progress ?: return null
        val at = played.resumeSeconds
        return VideoResume(
            fraction = (at.toFloat() / length).coerceIn(0f, 1f),
            secondsLeft = (length - at).coerceAtLeast(0),
            percentSeen = (played.seen.fraction(length) * 100).roundToInt(),
        )
    }

/**
 * A video's picture with what it shows at a glance: its length in the corner once known, a bar
 * along the foot for how far along it stops, dimmed once watched. Without a picture, a plain tile
 * with ▶ and the length. The caller sets the size.
 */
@Composable
internal fun VideoThumbnail(video: TopicItem.Video, modifier: Modifier = Modifier, cornerRadius: Int = 8) {
    val resume = video.resume
    val path = video.link.thumbnailPath
    Box(
        modifier
            .clip(RoundedCornerShape(cornerRadius.dp))
            .background(Sunken),
    ) {
        if (path != null) {
            // Watched recedes, so the eye goes to what's still to watch.
            LocalThumbnail(path, Modifier.fillMaxSize().alpha(if (video.watched) WATCHED_ALPHA else 1f))
            video.durationSeconds?.let { length ->
                Text(
                    formatDuration(length),
                    style = MetaStyle.copy(letterSpacing = 0.sp),
                    color = Ink,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 6.dp, bottom = if (resume != null) 9.dp else 6.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Void)
                        .padding(horizontal = 5.dp, vertical = 2.dp),
                )
            }
        } else {
            Column(
                Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text("▶", style = MetaStyle.copy(fontSize = 16.sp), color = if (video.watched) Muted else Synapse)
                video.durationSeconds?.let { Text(formatDuration(it), style = MetaStyle.copy(letterSpacing = 0.sp), color = Muted) }
            }
        }
        resume?.let { ResumeBar(it, Modifier.align(Alignment.BottomStart)) }
    }
}

@Composable
private fun ResumeBar(resume: VideoResume, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(3.dp)
            .background(Ink.copy(alpha = 0.25f))
            .clearAndSetSemantics {
                contentDescription = "${resume.percentSeen}% watched, ${spokenDuration(resume.secondsLeft)} left"
            },
    ) {
        Box(
            Modifier
                .fillMaxWidth(resume.fraction)
                .height(3.dp)
                .background(Synapse),
        )
    }
}

/** "1 hour 4 minutes", "8 minutes 42 seconds", "30 seconds": a length as TalkBack should say it. */
internal fun spokenDuration(seconds: Int): String {
    val h = seconds / 3600
    val m = seconds % 3600 / 60
    val s = seconds % 60
    fun part(n: Int, unit: String) = if (n == 1) "1 $unit" else "$n ${unit}s"
    return when {
        h > 0 -> listOfNotNull(part(h, "hour"), m.takeIf { it > 0 }?.let { part(it, "minute") }).joinToString(" ")
        m > 0 -> listOfNotNull(part(m, "minute"), s.takeIf { it > 0 }?.let { part(it, "second") }).joinToString(" ")
        else -> part(s, "second")
    }
}

private const val WATCHED_ALPHA = 0.45f
