package dev.kortex.links.domain

import dev.kortex.links.domain.model.Link
import dev.kortex.links.domain.model.matches
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkSearchTest {
    private val link = Link(1, "https://github.com/foo/llama-cpp", "Fast inference", 0, thumbnailPath = null, tags = listOf("MachineLearning"))

    @Test
    fun `matches the title, the address or a tag, ignoring case`() {
        assertTrue(link.matches("INFERENCE"))
        assertTrue(link.matches("llama"))
        assertTrue(link.matches("machinelearning"))
        assertFalse(link.matches("rust"))
    }

    @Test
    fun `an empty query matches everything`() {
        assertTrue(link.matches(""))
    }
}
