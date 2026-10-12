package dev.kortex.app.domain.chat

import dev.kortex.core.state.Attachment
import kotlinx.coroutines.flow.Flow

/** A saved conversation, as reopened from History or a share-result notification. */
data class ChatSession(val id: String, val title: String, val turns: List<ChatTurn>, val updatedAtMillis: Long)

/** One History row: enough to list a conversation without decoding its turns. */
data class ChatSessionSummary(val id: String, val title: String, val updatedAtMillis: Long)

/** Chat-session storage, shared by the chat screen and the background share runner. */
interface ChatSessionRepository {
    /** Saved conversations, newest first. */
    fun observeSummaries(): Flow<List<ChatSessionSummary>>

    suspend fun get(id: String): ChatSession?

    /** Creates or replaces session [id]. An existing session keeps its title; a new one is titled [fallbackTitle]. */
    suspend fun save(id: String, turns: List<ChatTurn>, fallbackTitle: String)

    /** Retitles session [id] without moving it in the list; later [save]s keep the new title. */
    suspend fun rename(id: String, title: String)

    suspend fun delete(id: String)
}

/** A new session's title: the query, or for an attachment-only message, what was attached. */
fun sessionTitle(query: String, attachments: List<Attachment>): String =
    query.trim().ifEmpty {
        attachments.firstOrNull()?.let { it.filename ?: "Shared ${kindOf(it)}" }.orEmpty()
    }

private fun kindOf(att: Attachment) = when {
    att.mimeType.startsWith("image/") -> "photo"
    att.mimeType == "application/pdf" -> "PDF"
    else -> "file"
}
