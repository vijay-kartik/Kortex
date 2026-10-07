package dev.kortex.app.data.local

import dev.kortex.app.domain.chat.ChatSession
import dev.kortex.app.domain.chat.ChatSessionRepository
import dev.kortex.app.domain.chat.ChatSessionSummary
import dev.kortex.app.domain.chat.ChatTurn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Stores each session's turns as one JSON column; the only place that format is read or written. */
class RoomChatSessionRepository(
    private val dao: ChatSessionDao,
    private val now: () -> Long = System::currentTimeMillis,
) : ChatSessionRepository {

    private val json = Json { ignoreUnknownKeys = true }

    override fun observeSummaries(): Flow<List<ChatSessionSummary>> =
        dao.getAll().map { rows -> rows.map { ChatSessionSummary(it.id, it.title, it.updatedAtMillis) } }

    override suspend fun get(id: String): ChatSession? = dao.getById(id)?.let {
        ChatSession(it.id, it.title, json.decodeFromString<List<ChatTurn>>(it.turnsJson), it.updatedAtMillis)
    }

    override suspend fun save(id: String, turns: List<ChatTurn>, fallbackTitle: String) {
        val title = dao.getById(id)?.title ?: fallbackTitle.take(TITLE_LENGTH)
        dao.upsert(ChatSessionEntity(id = id, title = title, turnsJson = json.encodeToString(turns), updatedAtMillis = now()))
    }

    override suspend fun delete(id: String) = dao.deleteById(id)

    companion object {
        const val TITLE_LENGTH = 40
    }
}
