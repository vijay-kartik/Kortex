package dev.kortex.myinfo.topics.ui

import dev.kortex.myinfo.topics.domain.model.SavedLink
import dev.kortex.myinfo.topics.domain.model.SeenRange
import dev.kortex.myinfo.topics.domain.model.SeenRanges
import dev.kortex.myinfo.topics.domain.model.TopicItem
import dev.kortex.myinfo.topics.domain.model.VideoProgress
import dev.kortex.myinfo.topics.ui.common.resume
import dev.kortex.myinfo.topics.ui.detail.videoLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VideoCardTest {
    private val video = TopicItem.Video(
        id = 1,
        topicId = 7,
        addedAtMillis = 0,
        link = SavedLink(1, "https://youtu.be/dQw4w9WgXcQ", "Dubai in 3 days", thumbnailPath = "/thumbs/1.jpg"),
        durationSeconds = 842,
        watched = false,
    )

    private fun at(resume: Int, seenTo: Int = resume) =
        video.copy(progress = VideoProgress(resume, SeenRanges.Empty + SeenRange(0, seenTo), lastPlayedAtMillis = 0))

    @Test
    fun `never played: just the type, the length being on the thumbnail`() {
        assertEquals("VIDEO", videoLabel(video))
        assertNull(video.resume)
    }

    @Test
    fun `without a thumbnail the length goes in the label`() {
        assertEquals("VIDEO · 14:02", videoLabel(video.copy(link = video.link.copy(thumbnailPath = null))))
        assertEquals("VIDEO", videoLabel(video.copy(link = video.link.copy(thumbnailPath = null), durationSeconds = null)))
    }

    @Test
    fun `half-watched shows the time left and how far the bar goes`() {
        val playing = at(resume = 320)
        assertEquals("VIDEO · 8:42 LEFT", videoLabel(playing))
        val resume = playing.resume!!
        assertEquals(0.38f, resume.fraction, 0.001f)
        assertEquals(522, resume.secondsLeft)
        assertEquals(38, resume.percentSeen)
    }

    @Test
    fun `no time left shown where opening it would start over`() {
        assertNull("watched", at(320).copy(watched = true).resume)
        assertNull("nearly finished", at(820).resume)
        assertNull("barely started", at(2).resume)
        assertNull("length unknown", at(320).copy(durationSeconds = null).resume)
        assertEquals("VIDEO", videoLabel(at(820)))
    }

    @Test
    fun `a video kept on YouTube says so instead`() {
        assertEquals("VIDEO · OPENS YOUTUBE ↗", videoLabel(at(320).copy(embedBlocked = true)))
    }
}
