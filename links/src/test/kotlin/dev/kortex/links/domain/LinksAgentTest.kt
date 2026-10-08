package dev.kortex.links.domain

import dev.kortex.links.domain.agent.LinksAgent
import dev.kortex.links.domain.model.Link
import dev.kortex.links.domain.model.PageMetadata
import dev.kortex.links.domain.model.TagCount
import dev.kortex.links.domain.port.Clock
import dev.kortex.links.domain.usecase.FindOrSaveLink
import dev.kortex.links.domain.usecase.ObserveLinks
import dev.kortex.links.domain.usecase.ObserveTagCounts
import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LinksAgentTest {
    private val repository = FakeLinksRepository()
    private val pages = FakePageReader()
    private val agent = LinksAgent(
        ObserveLinks(repository),
        ObserveTagCounts(repository),
        FindOrSaveLink(repository, pages, Clock { NOW }),
        ZoneOffset.UTC,
    )

    @Before
    fun data() {
        // Newest first, as the repository answers.
        repository.links.value = listOf(
            Link(3, "https://example.com/compose", "Compose layouts", DAY_3, null, listOf("android")),
            Link(2, "https://kotlinlang.org/docs/flow.html", "Asynchronous Flow", DAY_2, null, listOf("android", "kotlin")),
            Link(1, "https://example.com/rust", "Rust ownership", DAY_1, null, emptyList()),
        )
        repository.tagCounts.value = listOf(TagCount("android", 2), TagCount("kotlin", 1))
    }

    @Test
    fun `finds by a word in the title, the address or a tag, newest first`() = runTest {
        val answer = agent.findLinks("FLOW", emptyList())
        assertTrue(answer.ok)
        assertEquals(
            "1 link:\n\"Asynchronous Flow\" https://kotlinlang.org/docs/flow.html · tags: android, kotlin · saved 2023-11-15",
            answer.text,
        )
        assertEquals(2, agent.findLinks("android", emptyList()).text.lines().size - 1)
        assertTrue(agent.findLinks("kotlinlang", emptyList()).text.startsWith("1 link:"))
    }

    @Test
    fun `a tag filter keeps only links carrying every tag, ignoring case`() = runTest {
        val answer = agent.findLinks(null, listOf("Android", "kotlin"))
        assertTrue(answer.text.startsWith("1 link:\n\"Asynchronous Flow\""))
        assertEquals(listOf("Compose layouts", "Asynchronous Flow"), titles(agent.findLinks(null, listOf("android")).text))
    }

    @Test
    fun `the limit caps the list and says how many more there are`() = runTest {
        val answer = agent.findLinks(null, emptyList(), limit = 2)
        assertEquals(listOf("Compose layouts", "Asynchronous Flow"), titles(answer.text))
        assertTrue(answer.text.startsWith("3 links:"))
        assertTrue(answer.text.endsWith("…and 1 more."))
    }

    @Test
    fun `no match says so and lists the tags to retry with`() = runTest {
        val answer = agent.findLinks("swift", emptyList())
        assertTrue(answer.ok)
        assertEquals("No saved links match. Tags: android, kotlin.", answer.text)

        repository.tagCounts.value = emptyList()
        assertEquals("No saved links match. There are no tags yet.", agent.findLinks(null, listOf("ios")).text)
    }

    @Test
    fun `lists every tag with its link count`() = runTest {
        assertEquals("android: 2 links\nkotlin: 1 link", agent.listTags().text)
    }

    @Test
    fun `saves a new address with its page title and tags`() = runTest {
        pages.pages[NEW] = PageMetadata(NEW, title = "Coroutines guide")

        val answer = agent.saveLink(NEW, null, listOf("kotlin", " coroutines ", "Kotlin"))

        assertTrue(answer.ok)
        assertEquals("Saved to Links: \"Coroutines guide\" $NEW · tags: kotlin, coroutines · saved 2023-11-14", answer.text)
        assertEquals(4, repository.links.value.size)
    }

    @Test
    fun `an address already saved is reported with its title and tags, not saved again`() = runTest {
        val answer = agent.saveLink("https://kotlinlang.org/docs/flow.html", "Other", listOf("reading"))

        assertTrue(answer.ok)
        assertEquals(
            "Already in Links, unchanged: \"Asynchronous Flow\" https://kotlinlang.org/docs/flow.html · tags: android, kotlin · saved 2023-11-15",
            answer.text,
        )
        assertEquals(3, repository.links.value.size)
        assertTrue(repository.tagWrites.isEmpty())
    }

    @Test
    fun `something that isn't a web address is sent back`() = runTest {
        assertFalse(agent.saveLink("kotlin flows", null, emptyList()).ok)
        assertTrue(repository.saves.isEmpty())
    }

    private fun titles(text: String) = text.lines().drop(1).filter { it.startsWith("\"") }.map { it.substringAfter('"').substringBefore('"') }

    private companion object {
        const val NOW = 1_700_000_000_000L // 2023-11-14 UTC
        const val DAY_1 = NOW - 86_400_000L
        const val DAY_2 = NOW + 86_400_000L
        const val DAY_3 = NOW + 2 * 86_400_000L
        const val NEW = "https://example.com/coroutines"
    }
}
