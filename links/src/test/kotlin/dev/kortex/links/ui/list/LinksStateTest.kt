package dev.kortex.links.ui.list

import dev.kortex.links.domain.model.Link
import dev.kortex.links.domain.model.TagCount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LinksStateTest {
    private val grinder = link(1, "Pepper grinder", "https://curaahome.com/grinder", "Kitchen", "Gifts")
    private val llama = link(2, "llama.cpp", "https://github.com/ggml/llama.cpp", "AI")
    private val recipe = link(3, "Dal makhani", "https://example.com/dal", "Kitchen")
    private val tags = listOf(TagCount("AI", 1), TagCount("Gifts", 1), TagCount("Kitchen", 2))
    private val loaded = LinksState(loading = false, links = listOf(grinder, llama, recipe), tags = tags)

    @Test
    fun `empty only once loaded with no links at all`() {
        assertFalse(LinksState().empty)
        assertTrue(LinksState(loading = false).empty)
        assertFalse(loaded.empty)
    }

    @Test
    fun `deleting the only link shows its undo row, not the empty state`() {
        val state = LinksState(loading = false, links = listOf(grinder), tags = tags, pendingDeletion = pending(grinder.id))

        assertFalse(state.empty)
        assertEquals(0, state.linkCount)
        assertEquals(listOf(grinder), state.visibleLinks)
    }

    @Test
    fun `search is trimmed and matches title, address or tag`() {
        assertEquals(listOf(llama), loaded.copy(query = "  GITHUB ").visibleLinks)
        assertEquals(listOf(grinder, recipe), loaded.copy(query = "kitchen").visibleLinks)
        assertEquals(loaded.links, loaded.copy(query = "   ").visibleLinks)
    }

    @Test
    fun `tag filters AND together, and a vanished tag stops filtering`() {
        assertEquals(listOf(grinder, recipe), loaded.copy(selectedTags = setOf("Kitchen")).visibleLinks)
        assertEquals(listOf(grinder), loaded.copy(selectedTags = setOf("Kitchen", "Gifts")).visibleLinks)

        val vanished = loaded.copy(selectedTags = setOf("Kitchen", "Deleted"))
        assertEquals(setOf("Kitchen"), vanished.activeTags)
        assertEquals(listOf(grinder, recipe), vanished.visibleLinks)
    }

    @Test
    fun `counts leave out the link in its undo window`() {
        val state = loaded.copy(pendingDeletion = pending(grinder.id))

        assertEquals(2, state.linkCount)
        assertEquals(listOf(TagCount("AI", 1), TagCount("Gifts", 0), TagCount("Kitchen", 1)), state.tagCounts)
        assertEquals(pending(grinder.id), state.visiblePendingDeletion)
    }

    @Test
    fun `a pending link that vanished some other way stops counting`() {
        val state = loaded.copy(links = listOf(llama, recipe), pendingDeletion = pending(grinder.id))

        assertNull(state.visiblePendingDeletion)
        assertEquals(2, state.linkCount)
        assertEquals(tags, state.tagCounts)
    }

    @Test
    fun `the tray shows only while its link is on screen and not being deleted`() {
        val open = loaded.copy(optionsLinkId = grinder.id)
        assertEquals(grinder.id, open.openOptionsLinkId)
        assertEquals(grinder, open.openLink)

        assertNull(open.copy(query = "llama").openOptionsLinkId)
        assertNull(open.copy(selectedTags = setOf("AI")).openOptionsLinkId)
        assertNull(open.copy(pendingDeletion = pending(grinder.id)).openOptionsLinkId)
        assertNull(open.copy(links = listOf(llama)).openLink)
    }

    @Test
    fun `normalizing closes a hidden tray for good, draft and all`() {
        val editing = loaded.copy(optionsLinkId = grinder.id, tagDraft = TagDraft(listOf("Kitchen")))
        assertEquals(editing, editing.normalized())

        val filtered = editing.copy(query = "llama").normalized()
        assertNull(filtered.optionsLinkId)
        assertNull(filtered.tagDraft)
        assertNull("undoing the filter mustn't reopen it", filtered.copy(query = "").openOptionsLinkId)
    }

    @Test
    fun `editor lists every tag, then draft names not saved yet`() {
        val state = loaded.copy(optionsLinkId = llama.id, tagDraft = TagDraft(listOf("AI", "kitchen", "Local models")))

        assertEquals(listOf("AI", "Gifts", "Kitchen", "Local models"), state.editorTags)
    }

    @Test
    fun `a typed name reuses a stored spelling and is added once`() {
        val state = loaded.copy(optionsLinkId = llama.id, tagDraft = TagDraft(listOf("AI", "Local models")))

        assertEquals(listOf("AI", "Local models", "Kitchen"), state.draftTagsWith("  kitchen "))
        assertEquals(listOf("AI", "Local models"), state.draftTagsWith("local MODELS"))
        assertEquals(listOf("AI", "Local models", "Rust"), state.draftTagsWith("Rust"))
        assertEquals(listOf("AI", "Local models"), state.draftTagsWith("   "))
        assertNull(loaded.draftTagsWith("Rust"))
    }

    @Test
    fun `closing saves only a changed draft, the typed name included`() {
        val unchanged = loaded.copy(optionsLinkId = grinder.id, tagDraft = TagDraft(listOf("Gifts", "Kitchen")))
        assertFalse(unchanged.draftEdited)
        assertNull("order doesn't count as a change", unchanged.tagsToSave(""))
        assertEquals(listOf("Gifts", "Kitchen", "AI"), unchanged.tagsToSave("ai"))

        val edited = unchanged.copy(tagDraft = TagDraft(listOf("Kitchen")))
        assertTrue(edited.draftEdited)
        assertEquals(listOf("Kitchen"), edited.tagsToSave(""))
        assertNull("no draft, nothing to save", unchanged.copy(tagDraft = null).tagsToSave("AI"))
    }

    @Test
    fun `toggling a draft tag adds or removes it`() {
        val draft = TagDraft(listOf("AI"))

        assertEquals(listOf("AI", "Kitchen"), draft.toggled("Kitchen").tags)
        assertEquals(emptyList<String>(), draft.toggled("AI").tags)
    }

    private fun link(id: Long, title: String, url: String, vararg tags: String) =
        Link(id, url, title, createdAtMillis = id, thumbnailPath = null, tags = tags.toList())

    private fun pending(linkId: Long) = PendingLinkDeletion(linkId, startedAtMillis = 0, deadlineMillis = 5_000)
}
