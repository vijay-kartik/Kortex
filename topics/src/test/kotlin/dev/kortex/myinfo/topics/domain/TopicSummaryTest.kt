package dev.kortex.myinfo.topics.domain

import dev.kortex.myinfo.topics.domain.model.ItemType
import dev.kortex.myinfo.topics.domain.model.Money
import dev.kortex.myinfo.topics.domain.model.SavedLink
import dev.kortex.myinfo.topics.domain.model.StoredFile
import dev.kortex.myinfo.topics.domain.model.SummaryDigest
import dev.kortex.myinfo.topics.domain.model.Topic
import dev.kortex.myinfo.topics.domain.model.TopicDetail
import dev.kortex.myinfo.topics.domain.model.TopicItem
import dev.kortex.myinfo.topics.domain.model.TopicSummary
import dev.kortex.myinfo.topics.domain.port.Clock
import dev.kortex.myinfo.topics.domain.usecase.SummarizeResult
import dev.kortex.myinfo.topics.domain.usecase.SummarizeTopic
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.ZoneId

class TopicSummaryTest {
    private val utc = ZoneId.of("UTC")
    private val topic = Topic(1, "Trip to Dubai", purpose = "4 nights in March", pinned = false, sections = ItemType.DefaultSections, createdAtMillis = 0, updatedAtMillis = 0)

    private val note = TopicItem.Note(1, 1, addedAtMillis = 10, text = "Metro closes 00:30")
    private val article = TopicItem.Article(
        2, 1, addedAtMillis = 20,
        link = SavedLink(1, "https://gov.uk/visa", "Visa rules", thumbnailPath = null),
        readingMinutes = null, read = false,
    )

    // 2023-11-14 in UTC.
    private val bill = TopicItem.Bill(
        3, 1, addedAtMillis = 30, title = "Hotel balance", amount = Money(428_050, "AED"),
        issuedAtMillis = null, dueAtMillis = 1_700_000_000_000, paid = false, file = null,
    )
    private val pinnedDoc = TopicItem.Doc(
        4, 1, addedAtMillis = 5, title = "Passport scan",
        file = StoredFile("/files/topic-files/1-passport.pdf", "application/pdf"), pageCount = 2, pinned = true,
    )

    private fun detail(vararg items: TopicItem, of: Topic = topic) = TopicDetail(of, items.toList())

    // ── SummaryDigest ─────────────────────────────────────────────

    @Test
    fun `items read pinned first, then newest first, each saying what it is and what's open`() {
        val digest = SummaryDigest.of(detail(note, article, bill, pinnedDoc), utc)

        assertEquals(
            listOf(
                "Document [pinned]: Passport scan (2 pages)",
                "Bill: Hotel balance — AED 4280.50 — unpaid, due 2023-11-14",
                "Article, unread: Visa rules (https://gov.uk/visa)",
                "Note: Metro closes 00:30",
            ),
            digest.lines,
        )
        assertEquals("Trip to Dubai", digest.topicName)
        assertEquals("4 nights in March", digest.purpose)
        assertEquals(4, digest.itemCount)
    }

    @Test
    fun `the other types read plainly too`() {
        val link = SavedLink(2, "https://youtu.be/a", "Dubai in 3 days", thumbnailPath = null)
        val lines = SummaryDigest.of(
            detail(
                TopicItem.Video(5, 1, 50, link, durationSeconds = null, watched = true),
                TopicItem.Link(6, 1, 40, link.copy(title = "")),
                TopicItem.Image(7, 1, 30, StoredFile("/f/i.jpg", "image/jpeg"), caption = null),
                bill.copy(id = 8, addedAtMillis = 20, paid = true),
                bill.copy(id = 9, addedAtMillis = 10, amount = Money(1_200, "JPY"), dueAtMillis = null),
            ),
            utc,
        ).lines

        assertEquals(
            listOf(
                "Video, watched: Dubai in 3 days (https://youtu.be/a)",
                // A link with no title is read by its address.
                "Link: https://youtu.be/a (https://youtu.be/a)",
                "Image: no caption",
                "Bill: Hotel balance — AED 4280.50 — paid",
                "Bill: Hotel balance — JPY 1200 — unpaid",
            ),
            lines,
        )
    }

    @Test
    fun `a big topic is read from its newest items, but its size is still known`() {
        val many = (1..80).map { TopicItem.Note(it.toLong(), 1, addedAtMillis = it.toLong(), text = "note $it") }

        val digest = SummaryDigest.of(TopicDetail(topic, many), utc)

        assertEquals(SummaryDigest.MAX_ITEMS, digest.lines.size)
        assertEquals(80, digest.itemCount)
        assertEquals("Note: note 80", digest.lines.first())
    }

    @Test
    fun `a long note is cut short and its whitespace flattened`() {
        val long = TopicItem.Note(1, 1, 0, "word\n\n" + "word ".repeat(200))

        val line = SummaryDigest.of(detail(long), utc).lines.single()

        assertEquals(SummaryDigest.MAX_LINE, line.length)
        assertTrue(line.endsWith("…"))
        assertTrue("no line breaks reach the prompt", '\n' !in line)
    }

    @Test
    fun `the fingerprint moves with anything a summary would say, and nothing else`() {
        val base = SummaryDigest.of(detail(note, article, bill), utc).fingerprint

        assertEquals("same topic, same fingerprint", base, SummaryDigest.of(detail(note, article, bill), utc).fingerprint)
        assertNotEquals("an article read", base, SummaryDigest.of(detail(note, article.copy(read = true), bill), utc).fingerprint)
        assertNotEquals("a bill paid", base, SummaryDigest.of(detail(note, article, bill.copy(paid = true)), utc).fingerprint)
        assertNotEquals("an item added", base, SummaryDigest.of(detail(note, article, bill, pinnedDoc), utc).fingerprint)
        assertNotEquals("an item removed", base, SummaryDigest.of(detail(note, article), utc).fingerprint)
        assertNotEquals("the purpose edited", base, SummaryDigest.of(detail(note, article, bill, of = topic.copy(purpose = "5 nights")), utc).fingerprint)
        assertNotEquals("the topic renamed", base, SummaryDigest.of(detail(note, article, bill, of = topic.copy(name = "Dubai")), utc).fingerprint)
        // Pinning the topic or its updated time aren't things a summary talks about.
        assertEquals(base, SummaryDigest.of(detail(note, article, bill, of = topic.copy(pinned = true, updatedAtMillis = 999)), utc).fingerprint)
    }

    @Test
    fun `an empty topic has an empty digest`() {
        assertTrue(SummaryDigest.of(detail(), utc).empty)
    }

    // ── SummarizeTopic ────────────────────────────────────────────

    private val repository = FakeTopicsRepository()
    private val summarizer = FakeTopicSummarizer(reply = "  You're planning 4 nights in Dubai; the hotel balance is still due.  ")
    private val summarize = SummarizeTopic(repository, summarizer, Clock { 5_000 })

    @Test
    fun `a summary is kept, trimmed, with the fingerprint of the topic it read`() = runTest {
        val detail = detail(note, article, bill)

        val result = summarize(detail)

        val expected = TopicSummary(
            topicId = 1,
            text = "You're planning 4 nights in Dubai; the hotel balance is still due.",
            generatedAtMillis = 5_000,
            fingerprint = SummaryDigest.of(detail).fingerprint,
        )
        assertEquals(SummarizeResult.Saved(expected), result)
        assertEquals(expected, repository.summaries.value[1L])
        assertEquals(3, summarizer.read.single().itemCount)
    }

    @Test
    fun `an empty topic isn't sent to the model at all`() = runTest {
        assertEquals(SummarizeResult.NothingToSummarize, summarize(detail()))
        assertTrue(summarizer.read.isEmpty())
    }

    @Test
    fun `a model that fails is reported, and the old summary stays`() = runTest {
        val old = TopicSummary(1, "Older summary", 1_000, "old")
        repository.saveSummary(old)
        summarizer.failure = IOException("Ollama Cloud is selected but no API key is set. Add one in Settings.")

        val result = summarize(detail(note))

        assertEquals(SummarizeResult.Failed("Ollama Cloud is selected but no API key is set. Add one in Settings."), result)
        assertEquals(old, repository.summaries.value[1L])
    }

    @Test
    fun `a model that answers with nothing is a failure, not an empty summary`() = runTest {
        summarizer.reply = "   "

        assertTrue(summarize(detail(note)) is SummarizeResult.Failed)
        assertTrue(repository.summaries.value.isEmpty())
    }
}
