package dev.kortex.app.domain.gmail

import android.content.Intent
import dev.kortex.core.gmail.GmailApiException

/** What asking for the connected Gmail account's read-only token came to. */
sealed interface GmailToken {
    /** [token] reads [account]'s mailbox. */
    data class Granted(val account: String, val token: String) : GmailToken

    /** Why there is no token. */
    sealed interface Denied : GmailToken

    /** No account is connected in Settings. */
    data object NotConnected : Denied

    /** The user has to allow Kortex to read the mailbox first: launch [intent], then ask again. */
    data class NeedsConsent(val intent: Intent) : Denied

    data class Failed(val message: String) : Denied
}

/**
 * The one owner of Gmail access: which account is connected in Settings and the read-only
 * token for it. `gmail_search` and Topics' mail both read through here.
 */
interface GmailAccess {
    /** A token for the connected account. */
    suspend fun token(): GmailToken

    /** A token for [account], which becomes the connected account once one is granted. */
    suspend fun connect(account: String): GmailToken

    /** Drops a [token] Gmail refused, so the next [token] call fetches a fresh one. */
    fun invalidate(token: String)

    /** Lets the user pick one of the device's Google accounts. */
    fun pickAccountIntent(): Intent
}

/**
 * Runs [call] with a token for the connected account, or hands the reason there is none to
 * [denied]. A 401 means the cached token has expired: it is dropped and [call] runs once more
 * with a fresh one.
 */
suspend fun <T> GmailAccess.withToken(
    denied: (GmailToken.Denied) -> T,
    call: suspend (GmailToken.Granted) -> T,
): T {
    val granted = when (val first = token()) {
        is GmailToken.Granted -> first
        is GmailToken.Denied -> return denied(first)
    }
    return try {
        call(granted)
    } catch (e: GmailApiException) {
        if (e.statusCode != 401) throw e
        invalidate(granted.token)
        when (val fresh = token()) {
            is GmailToken.Granted -> call(fresh)
            is GmailToken.Denied -> denied(fresh)
        }
    }
}
