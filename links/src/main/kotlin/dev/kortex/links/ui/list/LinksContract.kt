package dev.kortex.links.ui.list

import dev.kortex.links.domain.model.Link
import dev.kortex.links.domain.model.TagCount
import dev.kortex.links.domain.model.matches

/**
 * Links list (Figma: Links). Holds inputs only; everything the screen shows is derived below, so
 * it can be tested without a ViewModel.
 */
data class LinksState(
    /** Before the first read, so the empty state doesn't flash for users who have links. */
    val loading: Boolean = true,
    /** Every saved link, newest first, minus ones already deleted. The one in its undo window is still here. */
    val links: List<Link> = emptyList(),
    /** Every tag with its link count, unused ones too, by name. */
    val tags: List<TagCount> = emptyList(),
    /** The top bar's search text, as last reported. */
    val query: String = "",
    val selectedTags: Set<String> = emptySet(),
    val pendingDeletion: PendingLinkDeletion? = null,
    /** The card whose options tray the user opened. Read [openOptionsLinkId] instead. */
    val optionsLinkId: Long? = null,
    /** Non-null while the open card edits its tags. */
    val tagDraft: TagDraft? = null,
) {
    /** Only with no links at all: deleting the only link shows its undo row, not the empty state. */
    val empty: Boolean = !loading && links.isEmpty()

    /** The undo row only shows while its link is still there to show (sync may remove it first). */
    private val pendingLink: Link? = pendingDeletion?.let { pending -> links.firstOrNull { it.id == pending.linkId } }
    val visiblePendingDeletion: PendingLinkDeletion? = pendingDeletion?.takeIf { pendingLink != null }

    /** Selections for tags that no longer exist are ignored, so they can't hide every link. */
    val activeTags: Set<String> = selectedTags.filterTo(mutableSetOf()) { name -> tags.any { it.name == name } }

    /** Links matching the search and every active tag. The one in its undo window stays, to show as its undo row. */
    val visibleLinks: List<Link> = links.filter { it.matches(query.trim()) && it.tags.containsAll(activeTags) }

    /** Tag counts leave out the link in its undo window. */
    val tagCounts: List<TagCount> = tags.map { tag ->
        if (pendingLink != null && tag.name in pendingLink.tags) tag.copy(linkCount = tag.linkCount - 1) else tag
    }

    /** Saved links, not counting the one in its undo window. */
    val linkCount: Int = links.size - if (pendingLink != null) 1 else 0

    /** The tray shows only while its link is on screen; see [normalized] for closing it for good. */
    val openOptionsLinkId: Long? = optionsLinkId?.takeIf { id ->
        id != pendingDeletion?.linkId && visibleLinks.any { it.id == id }
    }
    val openLink: Link? = openOptionsLinkId?.let { id -> links.firstOrNull { it.id == id } }

    /** The tag editor's chips: every tag, then draft names not saved as tags yet. */
    val editorTags: List<String> = tags.map { it.name }.let { known ->
        known + tagDraft?.tags.orEmpty().filterNot { draft -> known.any { it.equals(draft, ignoreCase = true) } }
    }

    /** The draft differs from the link's tags; Done then reads as a save. */
    val draftEdited: Boolean = tagDraft != null && openLink != null && tagDraft.tags.toSet() != openLink.tags.toSet()

    /**
     * The draft with [typedName] added: trimmed, in the stored spelling of a tag or draft name it
     * matches ignoring case, and only once. A blank name leaves the draft as it is. Null without a draft.
     */
    fun draftTagsWith(typedName: String): List<String>? {
        val draft = tagDraft?.tags ?: return null
        val typed = typedName.trim().takeIf { it.isNotEmpty() } ?: return draft
        val name = editorTags.firstOrNull { it.equals(typed, ignoreCase = true) } ?: typed
        return if (name in draft) draft else draft + name
    }

    /** What closing the tray should save as the open link's tags, [typedName] included; null when nothing changed. */
    fun tagsToSave(typedName: String): List<String>? {
        val link = openLink ?: return null
        return draftTagsWith(typedName)?.takeIf { it.toSet() != link.tags.toSet() }
    }

    /**
     * A tray whose link is no longer on screen is closed, and its draft dropped, not just hidden:
     * unlike topics, links un-hide (clearing the search or a tag filter), and the tray mustn't
     * pop back open with them. The ViewModel applies this after every change.
     */
    fun normalized(): LinksState =
        if (optionsLinkId != null && openOptionsLinkId == null) copy(optionsLinkId = null, tagDraft = null) else this
}

/** A deleted link that stays restorable until [deadlineMillis] (wall clock). */
data class PendingLinkDeletion(
    val linkId: Long,
    val startedAtMillis: Long,
    val deadlineMillis: Long,
)

/** The open card's tag edits; nothing is written until the tray closes. */
data class TagDraft(
    /** Stored spellings, plus names not saved as tags yet. */
    val tags: List<String>,
    /** The "+ new tag" field is open. Its text lives in the screen. */
    val adding: Boolean = false,
) {
    fun toggled(tag: String): TagDraft = copy(tags = if (tag in tags) tags - tag else tags + tag)
}

/**
 * Intents carry ids, not [Link]s: the ViewModel looks the link up in its current state, so a
 * stale link captured by a lambda can't be acted on.
 */
sealed interface LinksIntent {
    data class QueryChanged(val query: String) : LinksIntent
    /** Selected tags narrow the list: a link must carry all of them. */
    data class ToggleTagFilter(val tag: String) : LinksIntent
    data class Copy(val linkId: Long) : LinksIntent
    /** Long-press. Does nothing on the card already open, so its draft survives. */
    data class ShowOptions(val linkId: Long) : LinksIntent
    /** Tap outside, back or Done. Saves the draft if it changed, including a name still in the new-tag field. */
    data class CloseOptions(val typedTagName: String = "") : LinksIntent
    data class Open(val linkId: Long) : LinksIntent
    data class Share(val linkId: Long) : LinksIntent
    /** Hides the link behind an undo row; a second delete ends the first one's window early. */
    data class Delete(val linkId: Long) : LinksIntent
    data object UndoDelete : LinksIntent
    data object EditTags : LinksIntent
    data class ToggleDraftTag(val tag: String) : LinksIntent
    data object StartNewTag : LinksIntent
    data class AddDraftTag(val name: String) : LinksIntent
    data object CreateLink : LinksIntent
}

/**
 * The copy animation starts in the screen on tap; the clipboard write follows through [CopyUrl] a
 * moment later, which the eye won't notice.
 */
sealed interface LinksEffect {
    data class CopyUrl(val url: String) : LinksEffect
    data class OpenUrl(val url: String) : LinksEffect
    data class Share(val url: String, val title: String) : LinksEffect
    data object OpenCreateLink : LinksEffect
}
