package dev.kortex.myinfo.topics.ui.email

import dev.kortex.myinfo.topics.domain.model.SavedEmail
import dev.kortex.myinfo.topics.domain.model.StoredFile
import dev.kortex.myinfo.topics.domain.port.EmailAttachment
import dev.kortex.myinfo.topics.domain.port.EmailMessage

/**
 * A kept email, read in the app. What the topic kept about it — subject, sender, date — shows at
 * once; the body and the rest of the header arrive when the mailbox answers.
 */
data class EmailReaderState(
    val email: SavedEmail,
    /** Null until the mailbox has answered, and when it couldn't. */
    val message: EmailMessage? = null,
    val loading: Boolean = true,
    /** Why the body isn't showing, until the next attempt starts. */
    val problem: EmailProblem? = null,
    /** The attachment being downloaded to open, by id; one at a time. */
    val opening: String? = null,
) {
    val subject: String = (message?.subject ?: email.subject).ifBlank { "(no subject)" }
    val from: String = message?.from ?: email.from
    val sentAtMillis: Long? = message?.sentAtMillis ?: email.sentAtMillis

    /** Worth another go: the network or the sign-in, not a mail that has gone. */
    val canRetry: Boolean = !loading && problem is EmailProblem.Failed
}

sealed interface EmailProblem {
    /** No mailbox is connected in Settings. */
    data object NotConnected : EmailProblem

    /** The mailbox no longer has this email. */
    data object Gone : EmailProblem

    data class Failed(val message: String) : EmailProblem
}

sealed interface EmailReaderIntent {
    data object Retry : EmailReaderIntent

    /** Download an attachment and open it in whichever app handles its type. */
    data class OpenAttachment(val attachment: EmailAttachment) : EmailReaderIntent

    /** Leave for the mail app — to its inbox, since no mail app opens one message from outside. */
    data object OpenMailApp : EmailReaderIntent
}

sealed interface EmailReaderEffect {
    data object OpenMailApp : EmailReaderEffect

    /** An attachment is downloaded and ready for another app to open. */
    data class OpenFile(val file: StoredFile) : EmailReaderEffect

    data class ShowMessage(val text: String) : EmailReaderEffect
}
