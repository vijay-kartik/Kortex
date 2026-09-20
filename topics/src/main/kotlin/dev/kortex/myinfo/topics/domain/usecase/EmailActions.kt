package dev.kortex.myinfo.topics.domain.usecase

import dev.kortex.myinfo.topics.domain.model.SavedEmail
import dev.kortex.myinfo.topics.domain.port.EmailDirectory
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
 * The ways back to a kept email, best first. [EmailRoute.appSearch] finds it inside the mail app,
 * which is where the user reads their mail; [EmailRoute.web] opens the mail itself in a browser.
 */
class RouteToEmail(private val directory: EmailDirectory) {
    operator fun invoke(email: SavedEmail) = EmailRoute(
        appSearch = directory.searchQueryFor(email),
        web = directory.addressOf(email),
    )
}

data class EmailRoute(val appSearch: String?, val web: String?) {
    /** Nothing kept about this email would lead back to it. */
    val nowhere: Boolean get() = appSearch == null && web == null
}
