package dev.kortex.links.domain

import dev.kortex.links.domain.model.AlreadySavedLink
import dev.kortex.links.domain.model.Link
import dev.kortex.links.domain.model.LinkDraft
import dev.kortex.links.domain.usecase.ObserveDuplicate
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ObserveDuplicateTest {
    private val repository = FakeLinksRepository()
    private val observeDuplicate = ObserveDuplicate(repository)

    @Test
    fun `an unusable address is no duplicate and isn't looked up`() = runTest {
        assertNull(observeDuplicate("example").first())
        assertTrue(repository.savedLinkQueries.isEmpty())
    }

    @Test
    fun `another spelling of a saved address names the saved link`() = runTest {
        repository.links.value = listOf(Link(1, "https://example.com/post", "A post", 0, thumbnailPath = null, tags = emptyList()))

        assertEquals(AlreadySavedLink("https://www.example.com/post/", "A post"), observeDuplicate("https://www.example.com/post/").first())
    }

    @Test
    fun `follows saves as they happen`() = runTest {
        val seen = mutableListOf<AlreadySavedLink?>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { observeDuplicate("https://example.com/post").toList(seen) }

        repository.saveLink(LinkDraft("https://example.com/post", "A post", emptyList()), nowMillis = 0)

        assertEquals(listOf(null, AlreadySavedLink("https://example.com/post", "A post")), seen)
    }
}
