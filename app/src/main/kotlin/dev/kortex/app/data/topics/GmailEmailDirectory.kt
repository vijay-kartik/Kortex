package dev.kortex.app.data.topics

import dev.kortex.app.data.auth.GmailAuthManager
import dev.kortex.app.data.settings.SettingsStore
import dev.kortex.core.gmail.GmailApi
import dev.kortex.core.gmail.GmailApiException
import dev.kortex.core.gmail.GmailInlineImage
import dev.kortex.core.gmail.GmailMessage
import dev.kortex.myinfo.topics.domain.model.SavedEmail
import dev.kortex.myinfo.topics.domain.port.AttachmentDownload
import dev.kortex.myinfo.topics.domain.port.EmailAttachment
import dev.kortex.myinfo.topics.domain.port.EmailDirectory
import dev.kortex.myinfo.topics.domain.port.EmailMessage
import dev.kortex.myinfo.topics.domain.port.EmailReadResult
import dev.kortex.myinfo.topics.domain.port.EmailSearchResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import java.util.Base64

/**
 * Topics' view of the user's Gmail, over the same account and read-only token the `gmail_search`
 * tool uses — connected once in Settings. A topic never copies a mail: it keeps the ids and the
 * few lines it shows, and [read] fetches the rest when the mail is opened.
 */
class GmailEmailDirectory(
    private val settings: SettingsStore,
    private val auth: GmailAuthManager,
    private val api: GmailApi = GmailApi(),
) : EmailDirectory {

    override suspend fun search(query: String, limit: Int): EmailSearchResult {
        val account = connectedAccount() ?: return EmailSearchResult.NotConnected
        val token = when (val access = tokenFor(account)) {
            is Access.Granted -> access.token
            is Access.Refused -> return EmailSearchResult.Failed(access.message)
        }
        return try {
            // Gmail ranks its own results; the ids come back newest first.
            val ids = api.listMessages(token, query, maxResults = limit)
            // One read per result either way — Gmail has no bulk read — but a metadata read
            // carries no body and no attachments, so the picker isn't waiting on whole mails.
            // The extra headers ride in the same response and cost nothing.
            EmailSearchResult.Found(
                ids.map { api.getMessage(token, it, GmailApi.FORMAT_METADATA, PICKER_HEADERS).toSavedEmail(account) },
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: GmailApiException) {
            EmailSearchResult.Failed(e.userMessage())
        } catch (e: Exception) {
            EmailSearchResult.Failed(e.message?.takeIf { it.isNotBlank() } ?: "Gmail couldn't be reached.")
        }
    }

    override suspend fun read(email: SavedEmail): EmailReadResult {
        val account = connectedAccount() ?: return EmailReadResult.NotConnected
        // Gmail's ids are per mailbox: another account's id finds nothing here, or worse, not this mail.
        val keptFrom = email.accountEmail?.takeIf { it.isNotBlank() }
        if (keptFrom != null && !keptFrom.equals(account, ignoreCase = true)) {
            return EmailReadResult.Failed("This email was kept from $keptFrom, but Settings has $account connected.")
        }
        val token = when (val access = tokenFor(account)) {
            is Access.Granted -> access.token
            is Access.Refused -> return EmailReadResult.Failed(access.message)
        }
        return try {
            val message = api.getMessage(token, email.messageId)
            val html = message.bodyHtml?.let { withInlineImages(it, message, token) }
            EmailReadResult.Read(message.toEmailMessage(html))
        } catch (e: CancellationException) {
            throw e
        } catch (e: GmailApiException) {
            // 404 for a deleted mail; 400 for an id this mailbox never issued.
            if (e.statusCode == 404 || e.statusCode == 400) EmailReadResult.Gone else EmailReadResult.Failed(e.userMessage())
        } catch (e: Exception) {
            EmailReadResult.Failed(e.message?.takeIf { it.isNotBlank() } ?: "Gmail couldn't be reached.")
        }
    }

    override suspend fun download(email: SavedEmail, attachment: EmailAttachment): AttachmentDownload {
        val account = connectedAccount()
            ?: return AttachmentDownload.Failed("Connect your Gmail account in Settings to open attachments.")
        val token = when (val access = tokenFor(account)) {
            is Access.Granted -> access.token
            is Access.Refused -> return AttachmentDownload.Failed(access.message)
        }
        return try {
            AttachmentDownload.Downloaded(api.getAttachment(token, email.messageId, attachment.id))
        } catch (e: CancellationException) {
            throw e
        } catch (e: GmailApiException) {
            AttachmentDownload.Failed(
                if (e.statusCode == 404) "${attachment.name} is no longer in your mailbox." else e.userMessage(),
            )
        } catch (e: Exception) {
            AttachmentDownload.Failed(e.message?.takeIf { it.isNotBlank() } ?: "Gmail couldn't be reached.")
        }
    }

    /**
     * [html] with each `cid:` image it shows swapped for the image itself, as a data URI, so the
     * reader can draw it without going back to the mailbox. An image that can't be had stays a
     * `cid:` reference, which draws as a broken image rather than failing the whole mail.
     */
    private suspend fun withInlineImages(html: String, message: GmailMessage, token: String): Html {
        var result = html
        val inlined = mutableSetOf<String>()
        var budget = INLINE_IMAGE_BUDGET_BYTES
        for (image in message.inlineImages) {
            val reference = "cid:${image.contentId}"
            if (!result.contains(reference, ignoreCase = true)) continue
            val bytes = image.bytes(message.id, token) ?: continue
            if (bytes.size > budget) continue
            budget -= bytes.size
            val dataUri = "data:${image.mimeType};base64,${Base64.getEncoder().encodeToString(bytes)}"
            result = result.replace(reference, dataUri, ignoreCase = true)
            image.attachmentId?.let { inlined += it }
        }
        return Html(result, inlined)
    }

    private suspend fun GmailInlineImage.bytes(messageId: String, token: String): ByteArray? =
        data ?: attachmentId?.let { id ->
            try {
                api.getAttachment(token, messageId, id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        }

    /** A mail's HTML ready to show, and the attachments now drawn inside it rather than listed. */
    private data class Html(val body: String, val inlinedAttachmentIds: Set<String>)

    private suspend fun connectedAccount(): String? = settings.gmailAccountEmail.first()?.takeIf { it.isNotBlank() }

    private suspend fun tokenFor(account: String): Access = when (val result = auth.getToken(account)) {
        is GmailAuthManager.AuthResult.Success -> Access.Granted(result.token)
        // Consent is asked for in Settings, where the account is set up.
        is GmailAuthManager.AuthResult.NeedsConsent ->
            Access.Refused("Kortex needs permission to read this mailbox. Reconnect the account in Settings.")
        is GmailAuthManager.AuthResult.Error -> Access.Refused(result.message)
    }

    private sealed interface Access {
        data class Granted(val token: String) : Access
        data class Refused(val message: String) : Access
    }

    private fun GmailApiException.userMessage(): String = when (statusCode) {
        401 -> "The Gmail sign-in has expired. Reconnect the account in Settings."
        403 -> "Kortex isn't allowed to read this mailbox. Check the account in Settings."
        else -> "Gmail couldn't be reached ($statusCode)."
    }

    private fun GmailMessage.toSavedEmail(account: String) = SavedEmail(
        messageId = id,
        threadId = threadId,
        subject = subject,
        from = from,
        snippet = snippet,
        sentAtMillis = internalDateMillis,
        rfc822MessageId = rfc822MessageId.takeIf { it.isNotBlank() },
        accountEmail = account,
    )

    private fun GmailMessage.toEmailMessage(html: Html?) = EmailMessage(
        subject = subject,
        from = from,
        to = to,
        cc = cc,
        sentAtMillis = internalDateMillis,
        bodyText = bodyText,
        bodyHtml = html?.body,
        // A logo or photo drawn in the body isn't something the user attached; listing it would be noise.
        attachments = attachments.filterNot { it.attachmentId in html?.inlinedAttachmentIds.orEmpty() }.map {
            EmailAttachment(id = it.attachmentId, name = it.filename, mimeType = it.mimeType, sizeBytes = it.size.toLong())
        },
    )

    private companion object {
        /** What a row in the picker shows, plus the id that outlives Gmail's own. */
        val PICKER_HEADERS = listOf("Subject", "From", "Message-ID")

        /** Inline images past this many bytes, all told, stay out of the page rather than bloat it. */
        const val INLINE_IMAGE_BUDGET_BYTES = 8 * 1024 * 1024
    }
}
