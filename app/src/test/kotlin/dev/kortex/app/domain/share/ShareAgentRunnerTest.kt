package dev.kortex.app.domain.share

import dev.kortex.app.domain.chat.ChatSession
import dev.kortex.app.domain.chat.ChatSessionRepository
import dev.kortex.app.domain.chat.ChatSessionSummary
import dev.kortex.app.domain.chat.ChatTurn
import dev.kortex.core.Agent
import dev.kortex.core.graph.AgentContext
import dev.kortex.core.llm.LlmChunk
import dev.kortex.core.llm.LlmProvider
import dev.kortex.core.llm.LlmRequest
import dev.kortex.core.llm.LlmResponse
import dev.kortex.core.log.Logger
import dev.kortex.core.observability.AgentRun
import dev.kortex.core.observability.AgentRunStore
import dev.kortex.core.observability.AgentRunSummary
import dev.kortex.core.state.AgentState
import dev.kortex.core.state.Attachment
import dev.kortex.core.state.Message
import dev.kortex.core.tool.ToolRegistry
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** [ShareAgentRunner] on a scripted model: what lands in the session, the run store and the notification. */
class ShareAgentRunnerTest {

    private val pdf = Attachment(mimeType = "application/pdf", dataBase64 = "", filename = "invoice.pdf")
    private val photo = Attachment(mimeType = "image/jpeg", dataBase64 = "")

    private var clock = 1_000L

    /** Answers every call with [answer]; each call takes 500ms on [clock] and costs 15 tokens. */
    private inner class FakeLlm(private val answer: String) : LlmProvider {
        val requests = mutableListOf<LlmRequest>()

        override suspend fun complete(req: LlmRequest, logger: Logger?): LlmResponse {
            requests += req
            clock += 500
            return LlmResponse(Message(Message.Role.ASSISTANT, answer), inputTokens = 10, outputTokens = 5)
        }

        override fun stream(req: LlmRequest): Flow<LlmChunk> = emptyFlow()
    }

    private class FakeSessions : ChatSessionRepository {
        val saved = mutableMapOf<String, Pair<List<ChatTurn>, String>>()

        override fun observeSummaries(): Flow<List<ChatSessionSummary>> = emptyFlow()
        override suspend fun get(id: String): ChatSession? = null
        override suspend fun save(id: String, turns: List<ChatTurn>, fallbackTitle: String) {
            saved[id] = turns to fallbackTitle
        }
        override suspend fun delete(id: String) {}
    }

    private class FakeRunStore : AgentRunStore {
        val runs = mutableListOf<AgentRun>()

        override suspend fun save(run: AgentRun) { runs += run }
        override fun observeSummaries(limit: Int): Flow<List<AgentRunSummary>> = emptyFlow()
        override suspend fun get(id: String): AgentRun? = runs.firstOrNull { it.id == id }
        override suspend fun clear() = runs.clear()
    }

    private data class Notification(val sessionId: String, val title: String, val answer: String)

    private val sessions = FakeSessions()
    private val runStore = FakeRunStore()
    private val notifications = mutableListOf<Notification>()

    /** Submits on a scope that `runBlocking` waits out, so the background run has finished on return. */
    private fun share(
        query: String,
        attachment: Attachment,
        llm: LlmProvider = FakeLlm("Total due: 42 EUR"),
        ask: suspend (AgentContext, String, Attachment) -> AgentState =
            { ctx, q, att -> Agent(ctx).ask(q, listOf(att)) },
    ): String = runBlocking {
        ShareAgentRunner(
            scope = this,
            llm = llm,
            tools = ToolRegistry(),
            sessions = sessions,
            runStore = runStore,
            notifier = { id, title, answer -> notifications += Notification(id, title, answer) },
            logger = Logger.NONE,
            now = { clock },
            ask = ask,
        ).submit(query, attachment)
    }

    @Test
    fun `a successful run saves the question and answer and notifies with the answer`() {
        val llm = FakeLlm("Total due: 42 EUR")
        val id = share("How much do I owe?", pdf, llm)

        val (turns, title) = sessions.saved.getValue(id)
        assertEquals(2, turns.size)
        assertEquals(Message.Role.USER, turns[0].message.role)
        assertEquals("How much do I owe?", turns[0].message.content)
        assertEquals(listOf(pdf), turns[0].message.attachments)
        assertEquals("Total due: 42 EUR", turns[1].message.content)
        assertEquals("How much do I owe?", title)

        val stats = turns[1].stats
        assertEquals(15 * llm.requests.size, stats.tokensUsed)
        assertEquals(clock - 1_000L, stats.durationMs)
        assertTrue(turns[1].reasoning.isNotEmpty())

        assertEquals(listOf(Notification(id, "How much do I owe?", "Total due: 42 EUR")), notifications)
        assertEquals(AgentRun.Status.COMPLETED, runStore.runs.single().status)
    }

    @Test
    fun `an empty answer saves the fallback message`() {
        val id = share("Summarise", pdf, FakeLlm(""))

        val answer = sessions.saved.getValue(id).first.last().message
        assertEquals(Message.Role.ASSISTANT, answer.role)
        assertEquals("The agent finished without producing an answer.", answer.content)
        assertEquals("The agent finished without producing an answer.", notifications.single().answer)
    }

    @Test
    fun `an agent exception saves the failure turn and a failed run and still notifies`() {
        val id = share("Summarise", pdf, ask = { _, _, _ -> throw IOException("disk full") })

        val turns = sessions.saved.getValue(id).first
        assertEquals(2, turns.size)
        val failure = turns.last().message.content
        assertEquals("Processing failed: disk full. Open the session and retry from the chat.", failure)
        assertEquals(AgentRun.Status.FAILED, runStore.runs.single().status)
        assertEquals(listOf(Notification(id, "Summarise", failure)), notifications)
    }

    @Test
    fun `a blank query is titled after the attachment's filename`() {
        val id = share("  ", pdf)

        assertEquals("invoice.pdf", sessions.saved.getValue(id).second)
        assertEquals("invoice.pdf", notifications.single().title)
    }

    @Test
    fun `a blank query on an unnamed attachment is titled after its kind`() {
        val id = share("", photo)

        assertEquals("Shared photo", sessions.saved.getValue(id).second)
    }

    @Test
    fun `a long query is cut to forty characters for the title`() {
        val query = "Please read this whole document and tell me everything in it"
        val id = share(query, pdf)

        assertEquals(query.take(40), sessions.saved.getValue(id).second)
    }
}
