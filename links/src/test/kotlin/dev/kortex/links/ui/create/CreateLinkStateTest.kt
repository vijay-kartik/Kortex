package dev.kortex.links.ui.create

import dev.kortex.links.domain.model.AlreadySavedLink
import dev.kortex.links.domain.model.LinkAnalysis
import dev.kortex.links.domain.model.LinkImageState
import dev.kortex.links.domain.model.PageReadPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CreateLinkStateTest {
    private val read = LinkAnalysis(URL, suggestedTitle = "Pepper grinder", phase = PageReadPhase.Done, imageUrl = IMAGE)
    private val state = CreateLinkState(tags = listOf("Gifts", "Kitchen", "Reading"), analysis = read)

    @Test
    fun `the page counts as unread until the analysis matches the field`() {
        assertEquals(PageReadPhase.Done, state.phaseFor("  $URL "))
        assertEquals(PageReadPhase.Idle, state.phaseFor("$URL/other"))
        assertEquals(PreviewImage.Loading(null), state.imageFor(URL))
        assertEquals(PreviewImage.Unknown, state.imageFor("$URL/other"))
    }

    @Test
    fun `the preview image follows the analysis`() {
        assertEquals(PreviewImage.Unknown, read.copy(phase = PageReadPhase.Idle).previewImage())
        assertEquals(PreviewImage.Unknown, read.copy(phase = PageReadPhase.ReadingPage).previewImage())
        assertEquals(PreviewImage.None, read.copy(imageUrl = null).previewImage())
        assertEquals(PreviewImage.Loading(null), read.previewImage())
        assertEquals(PreviewImage.Loading(0.4f), read.copy(image = LinkImageState.Loading(0.4f)).previewImage())
        assertEquals(PreviewImage.Ready("/a.jpg", 800, 600), read.copy(image = LinkImageState.Ready("/a.jpg", 800, 600)).previewImage())
        assertEquals(PreviewImage.Failed, read.copy(image = LinkImageState.Failed).previewImage())
    }

    @Test
    fun `the title suggestion only fills from the address in the field`() {
        assertEquals("Pepper grinder", state.titleSuggestionFor(URL))
        assertNull(state.titleSuggestionFor("https://example.com/other"))
    }

    @Test
    fun `a duplicate counts only for the address in the field, and not once saved`() {
        val duplicate = state.copy(alreadySaved = AlreadySavedLink(URL, "Pepper grinder"))

        assertEquals(AlreadySavedLink(URL, "Pepper grinder"), duplicate.duplicateOf(" $URL"))
        assertNull(duplicate.duplicateOf("$URL/other"))
        assertNull("the link just saved mustn't flash up", duplicate.copy(saved = true).duplicateOf(URL))
    }

    @Test
    fun `saving needs a usable address that isn't saved yet, once`() {
        assertTrue(state.canSave(URL))
        assertFalse(state.canSave("curaahome"))
        assertFalse(state.copy(alreadySaved = AlreadySavedLink(URL, "Pepper grinder")).canSave(URL))
        assertFalse("a second tap while saving", state.copy(saving = true).canSave(URL))
    }

    @Test
    fun `suggested tags lead, the rest keep their order`() {
        val suggested = state.copy(analysis = read.copy(suggestedTags = listOf("Reading", "Kitchen")))

        assertEquals(listOf("Reading", "Kitchen", "Gifts"), suggested.orderedTags)
        assertEquals(state.tags, state.orderedTags)
    }

    @Test
    fun `candidates leave out existing tags, ignoring case, and stop at three`() {
        val candidates = state.copy(analysis = read.copy(candidateTags = listOf("homeware", "kitchen", "grinder", "curaahome", "pepper")))

        assertEquals(listOf("homeware", "grinder", "curaahome"), candidates.candidateTags)
    }

    @Test
    fun `the hide choice resets when the analysed address changes`() {
        val hidden = state.copy(imageHidden = true)

        assertTrue("same page, later phase", hidden.withAnalysis(read.copy(image = LinkImageState.Failed)).imageHidden)
        assertFalse(hidden.withAnalysis(LinkAnalysis("https://example.com/other")).imageHidden)
    }

    @Test
    fun `a typed tag reuses its stored spelling`() {
        assertEquals("Kitchen", state.tagNamed("  kitchen "))
        assertEquals("Homeware", state.tagNamed("Homeware"))
        assertNull(state.tagNamed("   "))
        assertFalse(state.isNewTag("KITCHEN"))
        assertTrue(state.isNewTag("Homeware"))
    }

    @Test
    fun `adding a tag selects it once and closes the field`() {
        val adding = state.copy(addingTag = true, selectedTags = listOf("Gifts"))

        assertEquals(CreateLinkState(tags = state.tags, analysis = read, selectedTags = listOf("Gifts", "Kitchen")), adding.withTagAdded("Kitchen"))
        assertEquals(listOf("Gifts"), adding.withTagAdded("Gifts").selectedTags)
        assertEquals(listOf("Gifts", "Kitchen"), adding.toggledTag("Kitchen").selectedTags)
        assertEquals(emptyList<String>(), adding.toggledTag("Gifts").selectedTags)
    }

    private companion object {
        const val URL = "https://curaahome.com/products/curaa-automatic-pepper-grinder"
        const val IMAGE = "https://cdn.curaahome.com/grinder.jpg"
    }
}
