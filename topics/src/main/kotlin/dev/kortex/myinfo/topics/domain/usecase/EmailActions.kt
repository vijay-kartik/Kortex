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

/** Where a kept email opens in the user's mail app; null when it can't be linked to. */
class EmailWebAddress(private val directory: EmailDirectory) {
    operator fun invoke(email: SavedEmail): String? = directory.addressOf(email)
}
