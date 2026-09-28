package dev.kortex.links.domain

import dev.kortex.links.domain.model.LinkDraft
import dev.kortex.links.domain.model.LinkImageSource
import dev.kortex.links.domain.port.Clock
import dev.kortex.links.domain.repository.SaveLinkResult
import dev.kortex.links.domain.usecase.SaveLink
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class SaveLinkTest {
    private val repository = FakeLinksRepository()
    private val saveLink = SaveLink(repository, Clock { NOW })

    @Test
    fun `trims the address and title and stamps the clock's time`() = runTest {
        val result = saveLink(LinkDraft("  https://example.com/post ", " A post ", listOf("reading"), LinkImageSource.None, imageHidden = true))

        assertEquals(SaveLinkResult.Saved(1), result)
        assertEquals(
            LinkDraft("https://example.com/post", "A post", listOf("reading"), LinkImageSource.None, imageHidden = true) to NOW,
            repository.saves.single(),
        )
    }

    @Test
    fun `an address saved in the meantime is reported, not saved twice`() = runTest {
        saveLink(LinkDraft("https://example.com/post", "A post", emptyList()))

        assertEquals(SaveLinkResult.AlreadySaved, saveLink(LinkDraft("https://www.example.com/post/", "Again", emptyList())))
        assertEquals(1, repository.links.value.size)
    }

    private companion object {
        const val NOW = 1_700_000_000_000L
    }
}
