package dev.kortex.myinfo.topics.ui.capture

import dev.kortex.myinfo.topics.domain.model.ItemType
import dev.kortex.myinfo.topics.domain.model.LinkLookup
import dev.kortex.myinfo.topics.domain.model.PickedFile
import dev.kortex.myinfo.topics.domain.model.SavedEmail
import dev.kortex.myinfo.topics.domain.usecase.Detection
import dev.kortex.myinfo.topics.domain.usecase.MoneyAmount
import dev.kortex.myinfo.topics.domain.usecase.captureTypes
import dev.kortex.myinfo.topics.domain.usecase.defaultCaptureType
import dev.kortex.myinfo.topics.ui.common.TopicChoice

/**
 * Quick-capture sheet (Figma: Topics 1d). Typed text — the pasted text, the title, a bill's
 * amount and a new topic's name — lives in the sheet's fields and arrives with
 * [QuickCaptureIntent.Save]; everything picked rather than typed lives here. The ViewModel hears
 * about text changes so it can detect the type and look an address up.
 */
data class QuickCaptureState(
    val detection: Detection = Detection(ItemType.Note, url = null),
    val blank: Boolean = true,
    /** Copied into app storage the moment it is picked, so the sheet can show it. */
    val file: PickedFile? = null,
    /** A picked file is still being copied in. */
    val attaching: Boolean = false,
    /** An email picked from the mailbox; it stands in for the pasted text, as a file does. */
    val email: SavedEmail? = null,
    /** The mailbox picker is open over the sheet. */
    val pickingEmail: Boolean = false,
    val emailResults: List<SavedEmail> = emptyList(),
    val emailSearching: Boolean = false,
    /** Set when the mailbox couldn't be read; null while all is well. */
    val emailError: String? = null,
    /** No mail account is connected, so there is nothing to search. */
    val emailNotConnected: Boolean = false,
    /** The user's pick; ignored while it doesn't suit the text or the file. */
    val chosenType: ItemType? = null,
    /** For [Detection.url]; null until the lookup finishes. */
    val lookup: LinkLookup? = null,
    val lookingUp: Boolean = false,
    /** Every tag in Links, offered on a link. */
    val tagChoices: List<String> = emptyList(),
    /** The link's tags as they'll be saved: the lookup's until the user changes them. */
    val pickedTags: List<String> = emptyList(),
    /** The user picked or dropped a tag, so a lookup no longer replaces [pickedTags]. */
    val tagsTouched: Boolean = false,
    /** Every tag is on show, not just the first few. */
    val showingAllTags: Boolean = false,
    /** "+ Tag" is open: a new tag's name is being typed. */
    val addingTag: Boolean = false,
    /**
     * The new tag's name as typed. Held here rather than in the sheet, unlike the other typed
     * fields, because whatever the user does next adds it: it is a pick they've half made.
     */
    val newTagName: String = "",
    val billCurrency: String = MoneyAmount.defaultCurrency(),
    val billDueAtMillis: Long? = null,
    val billPaid: Boolean = false,
    /** The due-date picker is open over the sheet. */
    val pickingDueDate: Boolean = false,
    val topics: List<TopicChoice> = emptyList(),
    val selectedTopicId: Long? = null,
    /** "+ New topic" is open: the item goes into a topic named in its field. */
    val creatingTopic: Boolean = false,
    val saving: Boolean = false,
    val error: CaptureError? = null,
) {
    val types: List<ItemType> = captureTypes(detection, file, email)
    val type: ItemType = chosenType?.takeIf { it in types } ?: defaultCaptureType(detection, file, email)

    /** There is something to save: text typed, a file attached, or an email picked. */
    val hasContent: Boolean = file != null || email != null || !blank

    /** The type on screen is the one detected, not a change the user made. */
    val typeIsDetected: Boolean = hasContent && type == defaultCaptureType(detection, file, email)

    /** A file stands in for the pasted text, so only one of the two is on screen at a time. */
    val fileMode: Boolean = file != null || attaching

    /** So does an email. */
    val emailMode: Boolean = email != null

    /** Bills are the only type with fields of their own (Figma: Topics 1d, bill). */
    val showBillFields: Boolean = type == ItemType.Bill

    /** A doc and a bill from a file need a title of their own; a link's is optional, an image's is a caption. */
    val showTitleField: Boolean = when (type) {
        ItemType.Link, ItemType.Article, ItemType.Video -> detection.url != null
        ItemType.Doc, ItemType.Image -> file != null
        ItemType.Bill -> file != null
        // An email is shown as itself; its subject isn't the user's to write.
        ItemType.Note, ItemType.Email -> false
    }

    /** A link carries its Links tags; every other type has none. */
    val showTagFields: Boolean = type.isLink && detection.url != null

    /**
     * The tags as the sheet lists them: the lookup's and the user's picks first, then the rest
     * of Links' tags (only the first few, unless [showingAllTags]), and last the tags the user
     * named here, so one just added shows where it was typed.
     */
    private val knownTags: List<String> =
        (lookup?.tags.orEmpty() + pickedTags + tagChoices).distinctBy { it.lowercase() }
    private val typedTags: List<String> = pickedTags.filterNot { picked ->
        (lookup?.tags.orEmpty() + tagChoices).any { it.equals(picked, ignoreCase = true) }
    }
    private val leadingTagCount: Int =
        (lookup?.tags.orEmpty() + pickedTags).distinctBy { it.lowercase() }.size - typedTags.size
    private val listedTags: List<String> = knownTags - typedTags.toSet()
    private val shownTagCount: Int =
        if (showingAllTags) listedTags.size else maxOf(leadingTagCount, TAGS_SHOWN_FOLDED).coerceAtMost(listedTags.size)
    val tagsOnOffer: List<String> = listedTags.take(shownTagCount) + typedTags
    val hiddenTagCount: Int = listedTags.size - shownTagCount

    fun isPicked(tag: String): Boolean = pickedTags.any { it.equals(tag, ignoreCase = true) }

    /**
     * The tags to save with a link, or null to leave a saved link's alone: before the lookup is
     * in and while the user hasn't touched them, the sheet doesn't know the link's tags yet.
     */
    val tagsToSave: List<String>? = pickedTags.takeIf { tagsTouched || lookup != null }

    val canSave: Boolean = hasContent && !saving && !attaching && (creatingTopic || selectedTopicId != null)

    private companion object {
        /** Enough to pick from at a glance; the rest wait behind "+ N more". */
        const val TAGS_SHOWN_FOLDED = 8
    }
}

enum class CaptureError {
    AlreadyInTopic,
    BillTitleBlank,
    BillAmountInvalid,
    NewTopicNameBlank,
    NewTopicNameTaken,

    /** The picked file couldn't be read, or was too big to keep. */
    FileUnreadable,
}

sealed interface QuickCaptureIntent {
    data class TextChanged(val text: String) : QuickCaptureIntent
    data class ChooseType(val type: ItemType) : QuickCaptureIntent

    /** A file came back from the picker, as its `content://` address. */
    data class AttachFile(val uri: String) : QuickCaptureIntent
    data object RemoveFile : QuickCaptureIntent

    // ── Picking an email ──
    data object StartPickingEmail : QuickCaptureIntent
    data object CancelPickingEmail : QuickCaptureIntent
    data class EmailQueryChanged(val query: String) : QuickCaptureIntent
    data class PickEmail(val email: SavedEmail) : QuickCaptureIntent
    data object RemoveEmail : QuickCaptureIntent

    /** The amount changed, so an error about the old one no longer applies. */
    data object BillAmountEdited : QuickCaptureIntent
    data class ChooseCurrency(val currency: String) : QuickCaptureIntent
    data object OpenDueDate : QuickCaptureIntent
    data object CloseDueDate : QuickCaptureIntent
    data class SetDueDate(val atMillis: Long?) : QuickCaptureIntent
    data class SetPaid(val paid: Boolean) : QuickCaptureIntent

    // ── A link's tags ──
    data class ToggleTag(val tag: String) : QuickCaptureIntent
    data object ShowAllTags : QuickCaptureIntent
    data object StartNewTag : QuickCaptureIntent
    data class NewTagNameChanged(val name: String) : QuickCaptureIntent

    /**
     * Adds the tag being typed; a blank name just closes the field. Any other intent does the
     * same first, so a tag isn't lost by tapping on to something else.
     */
    data object AddTag : QuickCaptureIntent

    data class SelectTopic(val topicId: Long) : QuickCaptureIntent
    data object StartNewTopic : QuickCaptureIntent

    /** The new topic's name changed, so an error about the old one no longer applies. */
    data object NewTopicNameEdited : QuickCaptureIntent

    /** Everything the sheet's own fields hold, at the moment Save was tapped. */
    data class Save(
        val text: String,
        val title: String,
        val newTopicName: String,
        val billAmount: String,
    ) : QuickCaptureIntent
}

sealed interface QuickCaptureEffect {
    data class Saved(val topicId: Long, val topicName: String) : QuickCaptureEffect
}
