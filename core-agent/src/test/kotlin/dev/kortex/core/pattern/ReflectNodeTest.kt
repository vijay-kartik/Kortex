package dev.kortex.core.pattern

import dev.kortex.core.graph.AgentContext
import dev.kortex.core.llm.LlmChunk
import dev.kortex.core.llm.LlmProvider
import dev.kortex.core.llm.LlmRequest
import dev.kortex.core.llm.LlmResponse
import dev.kortex.core.log.Logger
import dev.kortex.core.state.AgentState
import dev.kortex.core.state.Message
import dev.kortex.core.state.ToolCall
import dev.kortex.core.state.TraceEvent
import dev.kortex.core.tool.ToolGovernor
import dev.kortex.core.tool.ToolRegistry
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * ReflectNode with a canned reviewer (T2.1/T2.2): the real prompt is built from the
 * run's tool calls AND their results, the canned "REVISE: ..." / "OK" completions are
 * parsed by the real (unchanged) logic, and a revision feedback turn is appended only
 * on REVISE. No network — the provider records the request and returns a fixture.
 */
class ReflectNodeTest {

    /** Records the review request and returns a canned reviewer verdict. */
    private class CannedReviewer(private val reply: String) : LlmProvider {
        var lastRequest: LlmRequest? = null
        override suspend fun complete(req: LlmRequest, logger: Logger?): LlmResponse {
            lastRequest = req
            return LlmResponse(Message(Message.Role.ASSISTANT, reply))
        }
        override fun stream(req: LlmRequest): Flow<LlmChunk> = flowOf(LlmChunk.Done)
    }

    private fun ctx(reviewer: CannedReviewer) = AgentContext(
        llm = reviewer,
        tools = ToolRegistry(),
        governor = ToolGovernor(),
    )

    /**
     * A non-trivial run (two successful tool calls, so ReflectPolicy does NOT skip):
     * the tools report Jane Doe as CEO while the answer claims John Smith.
     */
    private fun multiToolState(answer: String) = AgentState(
        messages = listOf(
            Message(Message.Role.SYSTEM, "You are Kortex."),
            Message(Message.Role.USER, "Who is the CEO of Acme?"),
            Message(
                Message.Role.ASSISTANT, "",
                toolCalls = listOf(ToolCall("c1", "web_search", "{\"query\":\"Acme CEO\"}")),
            ),
            Message(Message.Role.TOOL, "Acme announces Jane Doe as CEO (2026).", toolCallId = "c1"),
            Message(
                Message.Role.ASSISTANT, "",
                toolCalls = listOf(ToolCall("c2", "open_url", "{\"url\":\"https://acme.com/about\"}")),
            ),
            Message(Message.Role.TOOL, "About Acme — CEO: Jane Doe.", toolCallId = "c2"),
            Message(Message.Role.ASSISTANT, answer),
        ),
        trace = listOf(
            TraceEvent("react", "tool", "web_search->true", 0L),
            TraceEvent("react", "tool", "open_url->true", 0L),
        ),
    )

    @Test
    fun `answer contradicting the tool results gets REVISE and a feedback turn`() = runTest {
        val reviewer = CannedReviewer(
            "REVISE: (a) The answer names John Smith, but both tool results say Jane Doe is Acme's CEO.",
        )
        val state = multiToolState("The CEO of Acme is John Smith.")

        val out = ReflectNode().run(ctx(reviewer), state)

        out.scratch[ReflectNode.VERDICT] shouldBe ReflectNode.REVISE
        out.scratch[ReflectNode.COUNT] shouldBe "1"
        val feedbackTurn = out.messages.last()
        feedbackTurn.role shouldBe Message.Role.USER
        feedbackTurn.content shouldContain "Please revise your previous answer."
        feedbackTurn.content shouldContain "(a) The answer names John Smith"

        // T2.1 grounding: the review prompt pairs each call with its recorded result.
        val prompt = reviewer.lastRequest!!.messages.last().content
        prompt shouldContain "- web_search({\"query\":\"Acme CEO\"})"
        prompt shouldContain "Result: Acme announces Jane Doe as CEO (2026)."
        prompt shouldContain "Result: About Acme — CEO: Jane Doe."
    }

    @Test
    fun `answer consistent with the tool results gets OK and no feedback turn`() = runTest {
        val reviewer = CannedReviewer("OK")
        val state = multiToolState("Jane Doe is the CEO of Acme.")

        val out = ReflectNode().run(ctx(reviewer), state)

        out.scratch[ReflectNode.VERDICT] shouldBe ReflectNode.OK
        out.messages.size shouldBe state.messages.size // nothing appended
        out.trace.last().detail shouldBe "ok"
    }
}
