package dev.kortex.links.domain

import dev.kortex.links.domain.model.Link
import dev.kortex.links.domain.model.matches
import dev.kortex.links.domain.usecase.RenameLink
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RenameLinkTest {
    private val repository = FakeLinksRepository()
    private val renameLink = RenameLink(repository)

    private val grinder = Link(1, "https://curaahome.com/grinder", "Home | Curaa — Kitchen tools", createdAtMillis = 7, thumbnailPath = "thumb.jpg", tags = listOf("Kitchen", "Gifts"))

    @Test
    fun `writes the trimmed title and leaves tags, date and thumbnail alone`() = runTest {
        repository.links.value = listOf(grinder)

        renameLink(grinder.id, "  Auto pepper grinder ")

        assertEquals(listOf(grinder.id to "Auto pepper grinder"), repository.titleWrites)
        assertEquals(grinder.copy(title = "Auto pepper grinder"), repository.links.value.single())
    }

    @Test
    fun `a blank title clears it`() = runTest {
        repository.links.value = listOf(grinder)

        renameLink(grinder.id, "   ")

        assertEquals(listOf(grinder.id to ""), repository.titleWrites)
        assertEquals("", repository.links.value.single().title)
    }

    @Test
    fun `search finds the link by its new title`() = runTest {
        repository.links.value = listOf(grinder)

        renameLink(grinder.id, "Auto pepper grinder")

        assertTrue(repository.links.value.single().matches("pepper"))
    }
}
