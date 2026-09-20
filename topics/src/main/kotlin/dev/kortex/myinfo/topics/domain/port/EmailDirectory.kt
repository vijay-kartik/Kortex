package dev.kortex.myinfo.topics.domain.port

import dev.kortex.myinfo.topics.domain.model.SavedEmail

/**
 * The user's mailbox, as Topics sees it. Topics never hold a copy of an email: they keep what it
 * takes to show it and to open it where it lives. Supplied by the host app, which owns the
 * mail account and its sign-in.
 */
interface EmailDirectory {
    /** Emails matching [query], newest first. Never throws; failures come back as a result. */
    suspend fun search(query: String, limit: Int = SEARCH_LIMIT): EmailSearchResult

    /**
     * Where [email] can be opened on the web. Null when this email can't be linked to.
     */
    fun addressOf(email: SavedEmail): String?

    /**
     * A search that finds [email] again in the mail app, in that app's own search syntax —
     * narrow enough to land on the one mail. Null when nothing kept about it would find it.
     */
    fun searchQueryFor(email: SavedEmail): String?

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
