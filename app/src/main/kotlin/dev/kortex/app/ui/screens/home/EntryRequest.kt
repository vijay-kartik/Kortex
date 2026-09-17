package dev.kortex.app.ui.screens.home

/**
 * Where a notification tap, launcher shortcut or share asks the app to go. Held as state until
 * [RootScreen] has acted on it, then cleared, so each request is handled exactly once.
 */
sealed interface EntryRequest {
    /** Notification tap on a share-intake result → load that chat session. */
    data class OpenSession(val sessionId: String) : EntryRequest

    /** Shared link or "Save to links" shortcut → new-link screen; [url] is empty from the shortcut. */
    data class NewLink(val url: String = "") : EntryRequest

    /** Shared text or "Ask agent" shortcut → new chat with [draft] in the composer, not sent. */
    data class NewChat(val draft: String = "") : EntryRequest
}
