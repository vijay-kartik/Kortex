package dev.kortex.myinfo.topics.domain.port

import dev.kortex.myinfo.topics.domain.model.SavedEmail

/**
 * The user's mailbox, as Topics sees it. Topics never hold a copy of an email: they keep what it
 * takes to show it in the feed, and [read] fetches the rest from the mailbox when it is opened.
 * Supplied by the host app, which owns the mail account and its sign-in.
 */
interface EmailDirectory {
    /** Emails matching [query], newest first. Never throws; failures come back as a result. */
    suspend fun search(query: String, limit: Int = SEARCH_LIMIT): EmailSearchResult

    /** The whole of [email], fetched from the mailbox now. Never throws; failures come back as a result. */
    suspend fun read(email: SavedEmail): EmailReadResult

    /**
     * The contents of one of [email]'s attachments, as [read] listed it. Never throws; failures
     * come back as a result.
     */
    suspend fun download(email: SavedEmail, attachment: EmailAttachment): AttachmentDownload

    companion object {
        /** A pickable list, not a mailbox: enough to find the mail meant, few enough to be quick. */
        const val SEARCH_LIMIT = 12
    }
}

sealed interface EmailSearchResult {
    data class Found(val emails: List<SavedEmail>) : EmailSearchResult

    /** No mail account is connected yet; the user sets one up in Settings. */
    data object NotConnected : EmailSearchResult

    /** The mailbox couldn't be reached or refused; [message] is fit to show. */
    data class Failed(val message: String) : EmailSearchResult
}

sealed interface EmailReadResult {
    data class Read(val message: EmailMessage) : EmailReadResult

    /** No mail account is connected yet; the user sets one up in Settings. */
    data object NotConnected : EmailReadResult

    /** The mailbox no longer has it: deleted, or the topic kept it from another account. */
    data object Gone : EmailReadResult

    /** The mailbox couldn't be reached or refused; [message] is fit to show. */
    data class Failed(val message: String) : EmailReadResult
}

/**
 * An email as read from the mailbox, for reading in the app. A mail usually carries its body
 * twice: [bodyHtml] is how the sender laid it out and is what the reader shows when there is one;
 * [bodyText] is the plain-text part, for mail sent without HTML.
 */
data class EmailMessage(
    val subject: String,
    val from: String,
    val to: String,
    val cc: String,
    val sentAtMillis: Long?,
    val bodyText: String,
    val bodyHtml: String?,
    /** What's attached, not counting images drawn in the body. Fetched with [EmailDirectory.download]. */
    val attachments: List<EmailAttachment>,
)

/** A file attached to an email, still in the mailbox until it is downloaded. */
data class EmailAttachment(
    /** The mailbox's handle on it, good for downloading it while this read of the mail is fresh. */
    val id: String,
    val name: String,
    val mimeType: String,
    val sizeBytes: Long,
)

sealed interface AttachmentDownload {
    class Downloaded(val bytes: ByteArray) : AttachmentDownload

    /** The mailbox couldn't be reached or refused; [message] is fit to show. */
    data class Failed(val message: String) : AttachmentDownload
}
