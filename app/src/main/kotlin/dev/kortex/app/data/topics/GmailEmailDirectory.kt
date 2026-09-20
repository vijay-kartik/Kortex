package dev.kortex.app.data.topics

import dev.kortex.app.data.auth.GmailAuthManager
import dev.kortex.app.data.settings.SettingsStore
import dev.kortex.core.gmail.GmailApi
import dev.kortex.core.gmail.GmailApiException
import dev.kortex.core.gmail.GmailMessage
import dev.kortex.myinfo.topics.domain.model.SavedEmail
import dev.kortex.myinfo.topics.domain.port.EmailDirectory
import dev.kortex.myinfo.topics.domain.port.EmailSearchResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

/**
 * Topics' view of the user's Gmail, over the same account and read-only token the `gmail_search`
 * tool uses — connected once in Settings. A topic never copies a mail: it keeps the ids and the
 * few lines it shows, and [addressOf] leads back to the mail itself.
 */
class GmailEmailDirectory(
    private val settings: SettingsStore,
    private val auth: GmailAuthManager,
    private val api: GmailApi = GmailApi(),
) : EmailDirectory {

    override suspend fun search(query: String, limit: Int): EmailSearchResult {
        val account = settings.gmailAccountEmail.first()?.takeIf { it.isNotBlank() }
            ?: return EmailSearchResult.NotConnected
        val token = when (val result = auth.getToken(account)) {
            is GmailAuthManager.AuthResult.Success -> result.token
            // Consent is asked for in Settings, where the account is set up.
            is GmailAuthManager.AuthResult.NeedsConsent ->
                return EmailSearchResult.Failed("Kortex needs permission to read this mailbox. Reconnect the account in Settings.")
            is GmailAuthManager.AuthResult.Error -> return EmailSearchResult.Failed(result.message)
        }
        return try {
            // Gmail ranks its own results; the ids come back newest first.
            val ids = api.listMessages(token, query, maxResults = limit)
            EmailSearchResult.Found(ids.map { api.getMessage(token, it).toSavedEmail(account) })
        } catch (e: CancellationException) {
            throw e
        } catch (e: GmailApiException) {
            EmailSearchResult.Failed(
                when (e.statusCode) {
                    401 -> "The Gmail sign-in has expired. Reconnect the account in Settings."
                    403 -> "Kortex isn't allowed to read this mailbox. Check the account in Settings."
                    else -> "Gmail couldn't be reached (${e.statusCode})."
                },
            )
        } catch (e: Exception) {
            EmailSearchResult.Failed(e.message?.takeIf { it.isNotBlank() } ?: "Gmail couldn't be reached.")
        }
    }

    /**
     * A Gmail address for the message. Gmail's own id opens the mail directly; without one, a
     * search for the `Message-ID` header finds it, which is why that header is kept.
     */
    override fun addressOf(email: SavedEmail): String? {
        val mailbox = email.accountEmail?.takeIf { it.isNotBlank() } ?: DEFAULT_MAILBOX
        email.messageId.takeIf { it.isNotBlank() }?.let { return "$BASE/u/$mailbox/#all/$it" }
        val header = email.rfc822MessageId?.takeIf { it.isNotBlank() } ?: return null
        return "$BASE/u/$mailbox/#search/rfc822msgid%3A${header.urlEncoded()}"
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

    private fun String.urlEncoded(): String = java.net.URLEncoder.encode(this, "UTF-8")

    private companion object {
        const val BASE = "https://mail.google.com/mail"

        /** Gmail resolves the signed-in account when no mailbox is named. */
        const val DEFAULT_MAILBOX = "0"
    }
}
