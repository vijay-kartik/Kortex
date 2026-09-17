package dev.kortex.app.ui.screens.chat

/**
 * A session change asked of [ChatScreen] by another screen (history, notification, share).
 * Held as state until ChatScreen has applied it, then cleared.
 */
sealed interface ChatRequest {
    /** Open a saved conversation. */
    data class LoadSession(val sessionId: String) : ChatRequest

    /** Start a fresh conversation; a non-null [draft] replaces the composer text (not sent). */
    data class NewSession(val draft: String? = null) : ChatRequest
}
