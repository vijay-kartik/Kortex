package dev.kortex.links.domain

import dev.kortex.links.domain.model.Link
import dev.kortex.links.domain.model.PageMetadata
import dev.kortex.links.domain.port.Clock
import dev.kortex.links.domain.usecase.FindOrSaveLink
import dev.kortex.links.domain.usecase.FindOrSaveResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FindOrSaveLinkTest {
    private val repository = FakeLinksRepository()
    private val pages = FakePageReader()
    private val findOrSave = FindOrSaveLink(repository, pages, Clock { NOW })

    @Test
    fun `a new address is saved under the page's title with its tags, new ones created`() = runTest {
        pages.pages[URL] = PageMetadata(URL, title = "Kotlin flows")

        val result = findOrSave(" $URL ", title = null, tags = listOf("android", "kotlin"))

        assertEquals(FindOrSaveResult.Saved(Link(1, URL, "Kotlin flows", NOW, null, listOf("android", "kotlin"))), result)
        assertEquals(listOf("android", "kotlin"), repository.tagCounts.value.map { it.name })
    }

    @Test
    fun `a given title wins over the page's, and an unreadable page falls back to the address`() = runTest {
        findOrSave(URL, title = "My title", tags = null)
        findOrSave(OTHER, title = null, tags = null)

        assertEquals(listOf(OTHER to OTHER, URL to "My title"), repository.links.value.map { it.url to it.title })
        assertEquals(listOf(OTHER), pages.reads)
    }

    @Test
    fun `an address already saved is returned untouched, not saved twice`() = runTest {
        val existing = Link(7, URL, "Old title", 0, null, listOf("reading"))
        repository.links.value = listOf(existing)

        val result = findOrSave("https://www.example.com/flows/", title = "New title", tags = listOf("android"))

        assertEquals(FindOrSaveResult.AlreadySaved(existing), result)
        assertTrue(repository.saves.isEmpty())
        assertTrue(repository.tagWrites.isEmpty())
        assertTrue(pages.reads.isEmpty())
    }

    private companion object {
        const val NOW = 1_700_000_000_000L
        const val URL = "https://example.com/flows"
        const val OTHER = "https://example.com/other"
    }
}
