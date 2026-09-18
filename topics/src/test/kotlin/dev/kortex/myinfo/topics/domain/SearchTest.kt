package dev.kortex.myinfo.topics.domain

import dev.kortex.myinfo.topics.domain.model.ItemType
import dev.kortex.myinfo.topics.domain.model.Money
import dev.kortex.myinfo.topics.domain.model.SavedLink
import dev.kortex.myinfo.topics.domain.model.SearchCorpus
import dev.kortex.myinfo.topics.domain.model.SearchScope
import dev.kortex.myinfo.topics.domain.model.StoredFile
import dev.kortex.myinfo.topics.domain.model.Topic
import dev.kortex.myinfo.topics.domain.model.TopicItem
import dev.kortex.myinfo.topics.domain.model.highlight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchTest {
    private val dubai = topic(1, "Trip to Dubai", updatedAt = 100)
    private val jobs = topic(2, "Job switch prep", updatedAt = 200)

    private val metroNote = TopicItem.Note(1, 1, 50, "Metro red line closes 00:30 — book a Careem back")
    private val visaLink = TopicItem.Link(
        2, 1, 40,
        SavedLink(1, "https://gov.uk/check-uk-visa", "Check if you need a UK visa", thumbnailPath = null),
    )
    private val hotelBill = TopicItem.Bill(
        3, 1, 30, "Hotel balance", Money(50_000, "AED"),
        issuedAtMillis = null, dueAtMillis = null, paid = false, file = null,
    )
    private val cvDoc = TopicItem.Doc(4, 2, 20, "CV — Dubai roles", StoredFile("/f/cv.pdf", "application/pdf"), pageCount = 2)
    private val interviewNote = TopicItem.Note(5, 2, 10, "Ask about the metro commute")

    private val corpus = SearchCorpus(
        topics = listOf(dubai, jobs),
        itemsByTopic = mapOf(1L to listOf(metroNote, visaLink, hotelBill), 2L to listOf(cvDoc, interviewNote)),
    )

    @Test
    fun `nothing typed means no results at all`() {
        assertTrue(corpus.search("").empty)
        assertTrue(corpus.search("   ").empty)
    }

    @Test
    fun `a word is found in notes, link titles and doc titles alike, ignoring case`() {
        val results = corpus.search("METRO")

        assertEquals(2, results.itemCount)
        // A hit each, no name match: the more recently changed topic leads.
        assertEquals(listOf(jobs.id, dubai.id), results.groups.map { it.topic.id })
        assertEquals(listOf(5L, 1L), results.groups.flatMap { group -> group.hits.map { it.item.id } })
    }

    @Test
    fun `every word has to appear, not just one of them`() {
        assertEquals(1, corpus.search("uk visa").itemCount)
        // "visa" is in the link, "careem" in the note — no single item holds both.
        assertTrue(corpus.search("visa careem").groups.all { it.hits.isEmpty() })
    }

    @Test
    fun `a link is found by its address as well as its title`() {
        val hit = corpus.search("gov.uk").groups.single().hits.single()

        assertEquals(visaLink.id, hit.item.id)
        assertTrue("the address is highlighted", hit.detail!!.matched)
        assertFalse("the title doesn't hold it", hit.title.matched)
    }

    @Test
    fun `a matching topic name surfaces the topic even with no matching items`() {
        val results = corpus.search("switch")

        val group = results.groups.single()
        assertEquals(jobs.id, group.topic.id)
        assertTrue(group.nameMatched)
        assertTrue(group.hits.isEmpty())
        assertEquals(0, results.itemCount)
        assertEquals(1, results.topicCount)
    }

    @Test
    fun `scope narrows the items but never the names`() {
        // "dubai" is in both topic names' orbit: one topic name, one doc title.
        assertEquals(listOf(cvDoc.id), corpus.search("dubai", SearchScope.Files).groups.flatMap { g -> g.hits.map { it.item.id } })
        assertEquals(0, corpus.search("dubai", SearchScope.Bills).itemCount)
        // The trip topic is still offered: its name matched, and scope doesn't touch names.
        assertTrue(corpus.search("dubai", SearchScope.Bills).groups.any { it.topic.id == dubai.id && it.nameMatched })
    }

    @Test
    fun `bills are found by title, and by the currency they are in`() {
        assertEquals(listOf(hotelBill.id), corpus.search("hotel", SearchScope.Bills).groups.flatMap { g -> g.hits.map { it.item.id } })
        assertEquals(listOf(hotelBill.id), corpus.search("aed", SearchScope.Bills).groups.flatMap { g -> g.hits.map { it.item.id } })
    }

    @Test
    fun `name matches lead, then the busiest topics, then the most recently changed`() {
        val results = corpus.search("dubai")

        // "Trip to Dubai" matched by name; "Job switch prep" only has a matching doc.
        assertEquals(listOf(dubai.id, jobs.id), results.groups.map { it.topic.id })
    }

    @Test
    fun `hits carry where the word sits, so the row can pick it out`() {
        val hit = corpus.search("careem").groups.single().hits.single()

        assertEquals(listOf(37..42), hit.title.matches)
        assertEquals("careem", hit.title.text.substring(37, 43).lowercase())
    }

    // ── highlight ─────────────────────────────────────────────────

    @Test
    fun `every occurrence is marked, not only the first`() {
        assertEquals(listOf(0..1, 6..7), highlight("ab cd ab", listOf("ab")).matches)
    }

    @Test
    fun `overlapping and touching marks are merged into one`() {
        // "aaa" holds "aa" at 0 and "aaa" at 0; they become one run rather than two markers.
        assertEquals(listOf(0..2), highlight("aaa", listOf("aa", "aaa")).matches)
        // Runs that meet with nothing between them join up.
        assertEquals(listOf(0..5), highlight("abcdef", listOf("abc", "def")).matches)
        // A space between two words is not part of either, so they stay two marks.
        assertEquals(listOf(0..2, 4..7), highlight("red line", listOf("red", "line")).matches)
    }

    @Test
    fun `text with nothing to mark comes back plain`() {
        val plain = highlight("Metro", listOf("bus"))

        assertEquals("Metro", plain.text)
        assertFalse(plain.matched)
        assertTrue(highlight("", listOf("a")).text.isEmpty())
    }

    private fun topic(id: Long, name: String, updatedAt: Long) =
        Topic(id, name, purpose = null, pinned = false, sections = ItemType.DefaultSections, createdAtMillis = 0, updatedAtMillis = updatedAt)
}
