package dev.kortex.app.data.local

import dev.kortex.app.domain.chat.ChatSessionSummary
import dev.kortex.app.domain.chat.ChatTurn
import dev.kortex.app.domain.chat.ReasoningLine
import dev.kortex.app.domain.chat.ReasoningStats
import dev.kortex.core.log.Logger
import dev.kortex.core.state.Message
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** [RoomChatSessionRepository] against an in-memory DAO: the turns round-trip, and the title rule. */
class RoomChatSessionRepositoryTest {
    private val dao = FakeChatSessionDao()
    private var clock = 1_000L
    private val repository = RoomChatSessionRepository(dao, now = { clock })

    private val turns = listOf(
        ChatTurn(Message(Message.Role.USER, "What did I spend on food?")),
        ChatTurn(
            Message(Message.Role.ASSISTANT, "₹4,200 this month."),
            reasoning = listOf(ReasoningLine(Logger.Level.INFO, "Router", "finance")),
            stats = ReasoningStats(tokensUsed = 120, toolCalls = 1, durationMs = 900),
        ),
    )

    @Test
    fun `saved turns come back as they went in`() = runBlocking {
        repository.save("s1", turns, fallbackTitle = "What did I spend on food?")

        val session = repository.get("s1")!!
        assertEquals(turns, session.turns)
        assertEquals("What did I spend on food?", session.title)
        assertEquals(1_000L, session.updatedAtMillis)
    }

    @Test
    fun `a new session is titled with the first 40 characters of the fallback`() = runBlocking {
        repository.save("s1", turns, fallbackTitle = "x".repeat(60))

        assertEquals("x".repeat(40), repository.get("s1")!!.title)
    }

    @Test
    fun `a later save keeps the session's title and moves it to the top`() = runBlocking {
        repository.save("old", turns, fallbackTitle = "Older chat")
        clock = 2_000L
        repository.save("s1", turns, fallbackTitle = "First question")
        clock = 3_000L
        repository.save("old", turns + turns, fallbackTitle = "A follow-up")

        assertEquals("Older chat", repository.get("old")!!.title)
        assertEquals(turns + turns, repository.get("old")!!.turns)
        assertEquals(
            listOf(ChatSessionSummary("old", "Older chat", 3_000L), ChatSessionSummary("s1", "First question", 2_000L)),
            repository.observeSummaries().first(),
        )
    }

    @Test
    fun `renaming changes the title without moving the session`() = runBlocking {
        repository.save("old", turns, fallbackTitle = "Older chat")
        clock = 2_000L
        repository.save("s1", turns, fallbackTitle = "First question")
        clock = 3_000L
        repository.rename("old", "Groceries")

        assertEquals(
            listOf(ChatSessionSummary("s1", "First question", 2_000L), ChatSessionSummary("old", "Groceries", 1_000L)),
            repository.observeSummaries().first(),
        )
    }

    @Test
    fun `a later save keeps the renamed title`() = runBlocking {
        repository.save("s1", turns, fallbackTitle = "First question")
        repository.rename("s1", "Food spending")
        repository.save("s1", turns + turns, fallbackTitle = "A follow-up")

        assertEquals("Food spending", repository.get("s1")!!.title)
    }

    @Test
    fun `listing and saving sessions never read their turns`() = runBlocking {
        repository.save("s1", turns, fallbackTitle = "First question")
        repository.rename("s1", "Food spending")
        clock = 2_000L
        repository.save("s1", turns + turns, fallbackTitle = "A follow-up")

        assertEquals(listOf(ChatSessionSummary("s1", "Food spending", 2_000L)), repository.observeSummaries().first())
        assertEquals(0, dao.fullRowReads)
    }

    @Test
    fun `a new title is trimmed and cut to 40 characters`() = runBlocking {
        repository.save("s1", turns, fallbackTitle = "q")

        repository.rename("s1", "  Trip plans  ")
        assertEquals("Trip plans", repository.get("s1")!!.title)

        repository.rename("s1", "  " + "y".repeat(60))
        assertEquals("y".repeat(40), repository.get("s1")!!.title)
    }

    @Test
    fun `a blank title is ignored`() = runBlocking {
        repository.save("s1", turns, fallbackTitle = "First question")
        repository.rename("s1", "   ")

        assertEquals("First question", repository.get("s1")!!.title)
    }

    @Test
    fun `a deleted session is gone`() = runBlocking {
        repository.save("s1", turns, fallbackTitle = "q")
        repository.delete("s1")

        assertNull(repository.get("s1"))
        assertEquals(emptyList<ChatSessionSummary>(), repository.observeSummaries().first())
    }

    private class FakeChatSessionDao : ChatSessionDao {
        private val rows = MutableStateFlow<Map<String, ChatSessionEntity>>(emptyMap())

        /** Counts reads of whole rows, `turnsJson` included; only [RoomChatSessionRepository.get] should make them. */
        var fullRowReads = 0

        override fun observeHeaders(): Flow<List<ChatSessionHeader>> = rows.map { all ->
            all.values.sortedByDescending(ChatSessionEntity::updatedAtMillis)
                .map { ChatSessionHeader(it.id, it.title, it.updatedAtMillis) }
        }

        override suspend fun getById(id: String): ChatSessionEntity? {
            fullRowReads++
            return rows.value[id]
        }

        override suspend fun getTitle(id: String): String? = rows.value[id]?.title

        override suspend fun upsert(session: ChatSessionEntity) {
            rows.value = rows.value + (session.id to session)
        }

        override suspend fun updateTitle(id: String, title: String) {
            val row = rows.value[id] ?: return
            rows.value = rows.value + (id to row.copy(title = title))
        }

        override suspend fun deleteById(id: String) {
            rows.value = rows.value - id
        }
    }
}
