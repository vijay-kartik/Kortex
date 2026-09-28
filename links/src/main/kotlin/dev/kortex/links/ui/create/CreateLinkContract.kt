package dev.kortex.links.ui.create

import dev.kortex.links.domain.model.AlreadySavedLink
import dev.kortex.links.domain.model.LinkAnalysis
import dev.kortex.links.domain.model.LinkImageState
import dev.kortex.links.domain.model.PageReadPhase
import dev.kortex.links.domain.model.linkDomain

/**
 * The new-link form. The address, title and new-tag fields belong to the screen: the ViewModel
 * hears each address change and gets the title on Save. Whatever compares the state with a field
 * is a function taking the field's current text, since a copy kept in the state would lag it by a frame.
 */
data class CreateLinkState(
    /** Every tag's stored spelling, by name. */
    val tags: List<String> = emptyList(),
    /** What reading the last settled address found. It may trail the field; see [isAnalysisFor]. */
    val analysis: LinkAnalysis = LinkAnalysis(),
    /** The saved link the last reported address is. Read [duplicateOf] instead. */
    val alreadySaved: AlreadySavedLink? = null,
    val selectedTags: List<String> = emptyList(),
    /** The "+ new tag" field is open. Its text lives in the screen. */
    val addingTag: Boolean = false,
    /** The user hid the page's image. The choice belongs to [analysis]'s page; see [withAnalysis]. */
    val imageHidden: Boolean = false,
    val saving: Boolean = false,
    /** Saved: the form is on its way out, so the link it just saved mustn't flash up as a duplicate. */
    val saved: Boolean = false,
) {
    /** Existing tags that fit the page, best first. */
    val suggestedTags: List<String> = analysis.suggestedTags

    /** Suggestions lead, best match first; the rest keep their stored order. */
    val orderedTags: List<String> = suggestedTags.filter { it in tags } + tags.filterNot { it in suggestedTags }

    /** New tag names read off the page, minus tags the user has (ignoring case). One disappears as soon as it's created. */
    val candidateTags: List<String> = analysis.candidateTags
        .filterNot { candidate -> tags.any { it.equals(candidate, ignoreCase = true) } }
        .take(MAX_CANDIDATE_TAGS)

    val previewImage: PreviewImage = analysis.previewImage()

    /** The analysis was read from what's in the field (trimmed), not from an address typed before it. */
    fun isAnalysisFor(fieldUrl: String): Boolean = analysis.url == fieldUrl.trim()

    /** Until the analysis catches up with the field (typing, debounce), the page counts as unread. */
    fun phaseFor(fieldUrl: String): PageReadPhase = if (isAnalysisFor(fieldUrl)) analysis.phase else PageReadPhase.Idle

    fun imageFor(fieldUrl: String): PreviewImage = if (isAnalysisFor(fieldUrl)) previewImage else PreviewImage.Unknown

    /** Only a check made against what's in the field right now counts. */
    fun duplicateOf(fieldUrl: String): AlreadySavedLink? = alreadySaved?.takeIf { !saved && it.url == fieldUrl.trim() }

    /** The page's own title, once it belongs to what's in the field. */
    fun titleSuggestionFor(fieldUrl: String): String? = analysis.suggestedTitle?.takeIf { isAnalysisFor(fieldUrl) }

    fun canSave(fieldUrl: String): Boolean = linkDomain(fieldUrl) != null && duplicateOf(fieldUrl) == null && !saving

    /** A hide choice belongs to one page's image: another address starts shown again. */
    fun withAnalysis(next: LinkAnalysis): CreateLinkState =
        copy(analysis = next, imageHidden = imageHidden && next.url == analysis.url)

    /** [name] trimmed, in the stored spelling of a tag it matches ignoring case; null when blank. */
    fun tagNamed(name: String): String? {
        val trimmed = name.trim().ifEmpty { return null }
        return tags.firstOrNull { it.equals(trimmed, ignoreCase = true) } ?: trimmed
    }

    fun isNewTag(tag: String): Boolean = tags.none { it.equals(tag, ignoreCase = true) }

    fun toggledTag(tag: String): CreateLinkState =
        copy(selectedTags = if (tag in selectedTags) selectedTags - tag else selectedTags + tag)

    /** [tag] selected, once, and the new-tag field closed. */
    fun withTagAdded(tag: String): CreateLinkState =
        copy(selectedTags = if (tag in selectedTags) selectedTags else selectedTags + tag, addingTag = false)
}

/** The page's share image as the preview card sees it. */
sealed interface PreviewImage {
    /** The page hasn't been read, so it isn't known whether there is one. */
    data object Unknown : PreviewImage

    /** The page names no image (or couldn't be read). */
    data object None : PreviewImage

    data class Loading(val fraction: Float?) : PreviewImage

    data class Ready(val path: String, val width: Int, val height: Int) : PreviewImage

    data object Failed : PreviewImage
}

internal fun LinkAnalysis.previewImage(): PreviewImage = when {
    phase == PageReadPhase.Idle || phase == PageReadPhase.ReadingPage -> PreviewImage.Unknown
    imageUrl == null -> PreviewImage.None
    else -> when (val download = image) {
        null -> PreviewImage.Loading(fraction = null)
        is LinkImageState.Loading -> PreviewImage.Loading(download.fraction)
        is LinkImageState.Ready -> PreviewImage.Ready(download.path, download.width, download.height)
        LinkImageState.Failed -> PreviewImage.Failed
    }
}

sealed interface CreateLinkIntent {
    /** Every edit of the address field, and its first value (a shared or restored address). */
    data class UrlChanged(val url: String) : CreateLinkIntent
    data class ToggleTag(val tag: String) : CreateLinkIntent
    data object StartNewTag : CreateLinkIntent
    /** Back while the new-tag field is open. */
    data object CancelNewTag : CreateLinkIntent
    /** A typed name or a candidate chip. A blank name only closes the field; a new name is created right away. */
    data class AddTag(val name: String) : CreateLinkIntent
    /** Showing a failed image again also retries it. */
    data class SetImageHidden(val hidden: Boolean) : CreateLinkIntent
    data object RetryImage : CreateLinkIntent
    data class Save(val url: String, val title: String) : CreateLinkIntent
}

sealed interface CreateLinkEffect {
    /** The link is saved; the form closes. */
    data object Saved : CreateLinkEffect
}

private const val MAX_CANDIDATE_TAGS = 3
