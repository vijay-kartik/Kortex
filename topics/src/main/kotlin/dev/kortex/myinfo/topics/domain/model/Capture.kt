package dev.kortex.myinfo.topics.domain.model

/** An address as the Links library knows it, before it is saved into a topic. */
data class LinkLookup(
    /** The saved link's title if the address is in Links, else the page's own; null if neither is known. */
    val title: String?,
    /** Already in Links, so the topic will point at that link rather than save it again. */
    val inLinks: Boolean,
)

/** A file already copied into app storage, waiting to be saved into a topic. */
data class PickedFile(
    val file: StoredFile,
    /** Its name where it came from; it titles a doc unless the user types one. */
    val name: String,
    /** A picture, so it can be kept as an image rather than a doc. */
    val isImage: Boolean,
    /** Pages, for a doc the vault could count; null for everything else. */
    val pageCount: Int? = null,
)

/** A bill's own fields, as its form holds them: the amount is still the typed text. */
data class BillFields(
    val amount: String = "",
    val currency: String,
    val dueAtMillis: Long? = null,
    val paid: Boolean = false,
)

/** What quick capture has collected so far (Figma: Topics 1d). */
data class CaptureDraft(
    val type: ItemType,
    /** Pasted or typed: a link's address, a note, or a bill's title. Empty once a file is attached. */
    val text: String = "",
    /**
     * The second field: a link's, doc's or bill's title, or an image's caption. Blank means the
     * user left it alone.
     */
    val title: String = "",
    val file: PickedFile? = null,
    /** An email picked from the mailbox; it stands in for the text, as a file does. */
    val email: SavedEmail? = null,
    /** Only read when [type] is [ItemType.Bill]. */
    val bill: BillFields? = null,
)

/** Which topic a captured item goes into. */
sealed interface CaptureTarget {
    data class Existing(val topicId: Long) : CaptureTarget

    /** A topic created on the spot, named [name]. */
    data class New(val name: String) : CaptureTarget
}

sealed interface CaptureResult {
    data class Saved(val topicId: Long) : CaptureResult

    /** Nothing valid to save: blank text, or a link type for text that isn't an address. */
    data object Invalid : CaptureResult
    data object AlreadyInTopic : CaptureResult

    /** A bill without a title to call it by. */
    data object BillTitleBlank : CaptureResult

    /** A bill's amount is blank or isn't a number. */
    data object BillAmountInvalid : CaptureResult
    data object NewTopicNameBlank : CaptureResult
    data object NewTopicNameTaken : CaptureResult
}
