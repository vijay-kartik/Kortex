package dev.kortex.myinfo.topics.domain.usecase

import dev.kortex.myinfo.topics.domain.model.SavedEmail
import dev.kortex.myinfo.topics.domain.model.StoredFile
import dev.kortex.myinfo.topics.domain.port.AttachmentCache
import dev.kortex.myinfo.topics.domain.port.AttachmentDownload
import dev.kortex.myinfo.topics.domain.port.EmailAttachment
import dev.kortex.myinfo.topics.domain.port.EmailDirectory
import dev.kortex.myinfo.topics.domain.port.EmailReadResult
import dev.kortex.myinfo.topics.domain.port.EmailSearchResult

/**
 * Emails matching what the user typed, for picking one to keep in a topic. A blank query asks
 * the mailbox nothing.
 */
class SearchEmails(private val directory: EmailDirectory) {
    suspend operator fun invoke(query: String, limit: Int = EmailDirectory.SEARCH_LIMIT): EmailSearchResult =
        if (query.isBlank()) EmailSearchResult.Found(emptyList()) else directory.search(query.trim(), limit)
}

/**
 * A kept email, read in full from the mailbox so it can be shown in the app. The mail apps give
 * no way to open one message from outside, so this is how a kept email is opened.
 */
class ReadEmail(private val directory: EmailDirectory) {
    suspend operator fun invoke(email: SavedEmail): EmailReadResult = directory.read(email)
}

/**
 * One of a kept email's attachments, downloaded from the mailbox and put where another app can
 * open it.
 */
class FetchEmailAttachment(
    private val directory: EmailDirectory,
    private val cache: AttachmentCache,
) {
    suspend operator fun invoke(email: SavedEmail, attachment: EmailAttachment): FetchedAttachment =
        when (val download = directory.download(email, attachment)) {
            is AttachmentDownload.Failed -> FetchedAttachment.Failed(download.message)
            is AttachmentDownload.Downloaded ->
                cache.put(attachment.name, attachment.mimeType, download.bytes)
                    ?.let { FetchedAttachment.Ready(it) }
                    ?: FetchedAttachment.Failed("Couldn't save ${attachment.name} on this phone.")
        }
}

sealed interface FetchedAttachment {
    data class Ready(val file: StoredFile) : FetchedAttachment

    /** [message] is fit to show. */
    data class Failed(val message: String) : FetchedAttachment
}
