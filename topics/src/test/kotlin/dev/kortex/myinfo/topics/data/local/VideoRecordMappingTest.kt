package dev.kortex.myinfo.topics.data.local

import dev.kortex.myinfo.topics.domain.model.SavedLink
import dev.kortex.myinfo.topics.domain.model.SeenRange
import dev.kortex.myinfo.topics.domain.model.SeenRanges
import dev.kortex.myinfo.topics.domain.model.TopicItem
import dev.kortex.myinfo.topics.domain.model.VideoProgress
import dev.kortex.myinfo.topics.domain.model.VideoRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VideoRecordMappingTest {
    private val row = TopicItemEntity(id = 4, topicId = 1, type = "Video", addedAtMillis = 10, linkId = 8, durationSeconds = 600)
    private val links = mapOf(8L to SavedLink(8, "https://youtu.be/dQw4w9WgXcQ", "Dubai in 3 days", thumbnailPath = null))

    @Test
    fun `a video never played has no progress`() {
        val video = row.toDomain(links) as TopicItem.Video
        assertNull(video.progress)
        assertEquals(VideoRecord(durationSeconds = 600, watched = false, progress = null, embedBlocked = false), video.record)
    }

    @Test
    fun `a record written to the row reads back the same`() {
        val record = VideoRecord(
            durationSeconds = 610,
            watched = true,
            progress = VideoProgress(320, SeenRanges.Empty + SeenRange(0, 190) + SeenRange(300, 320), lastPlayedAtMillis = 99),
            embedBlocked = true,
        )
        val written = row.withVideoRecord(record)

        assertEquals("0-190,300-320", written.seenRanges)
        assertEquals(record, written.videoRecord())
        assertEquals(record, (written.toDomain(links) as TopicItem.Video).record)
    }
}
