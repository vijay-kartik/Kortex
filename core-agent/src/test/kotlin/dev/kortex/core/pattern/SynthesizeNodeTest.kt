package dev.kortex.core.pattern

import dev.kortex.core.graph.AgentContext
import dev.kortex.core.llm.LlmChunk
import dev.kortex.core.llm.LlmProvider
import dev.kortex.core.llm.LlmRequest
import dev.kortex.core.llm.LlmResponse
import dev.kortex.core.log.Logger
import dev.kortex.core.state.AgentState
import dev.kortex.core.state.Goal
import dev.kortex.core.state.Message
import dev.kortex.core.state.Plan
import dev.kortex.core.state.PlanStep
import dev.kortex.core.tool.ToolGovernor
import dev.kortex.core.tool.ToolRegistry
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * SynthesizeNode (T4.2, pattern 6) with a canned composer: the real SynthesizePrompt is
 * built from the plan's steps (FAILED gaps visible), the answer lands in state.messages
 * as an ASSISTANT turn (reflect/app see it like a react answer), the evidence digest
 * lands in scratch["plan_evidence"], and revise passes carry the prior answer + reviewer
 * feedback. No network.
 */
class SynthesizeNodeTest {

    private class CannedComposer(private val reply: String) : LlmProvider {
        var lastRequest: LlmRequest? = null
        override suspend fun complete(req: LlmRequest, logger: Logger?): LlmResponse {
            lastRequest = req
            return LlmResponse(Message(Message.Role.ASSISTANT, reply))
        }
        override fun stream(req: LlmRequest): Flow<LlmChunk> = flowOf(LlmChunk.Done)
    }

    private fun ctx(composer: CannedComposer) = AgentContext(
        llm = composer,
        tools = ToolRegistry(),
        governor = ToolGovernor(),
    )

    private val steps = listOf(
        PlanStep("Find A's price", PlanStep.Status.DONE, "A costs \$999"),
        PlanStep("Find B's price", PlanStep.Status.FAILED, "no answer within 4 iterations"),
    )

    private fun state(extraMessages: List<Message> = emptyList(), scratch: Map<String, String> = emptyMap()) =
        AgentState(
            messages = listOf(
                Message(Message.Role.SYSTEM, "You are Kortex."),
                Message(Message.Role.USER, "Compare A and B on price"),
            ) + extraMessages,
            goal = Goal("Compare A and B on price"),
            plan = Plan(steps),
            scratch = scratch,
        )

    @Test
    fun `appends the answer, marks done, and stores the evidence digest`() = runTest {
        val composer = CannedComposer("A costs \$999. B's price could not be determined.")
        val out = SynthesizeNode().run(ctx(composer), state())

        out.done shouldBe true
        val answer = out.messages.last()
        answer.role shouldBe Message.Role.ASSISTANT
        answer.content shouldBe "A costs \$999. B's price could not be determined."

        val evidence = out.scratch[SynthesizeNode.EVIDENCE_KEY]!!
        evidence shouldContain "1. [DONE] Find A's price: A costs \$999"
        evidence shouldContain "2. [FAILED] Find B's price: no answer within 4 iterations"
        out.trace.last().detail shouldBe "answer (2 steps, 1 failed)"
    }

    @Test
    fun `the composer prompt shows FAILED steps so gaps can be noted`() = runTest {
        val composer = CannedComposer("answer")
        SynthesizeNode().run(ctx(composer), state())

        val prompt = composer.lastRequest!!.messages.last().content
        prompt shouldContain "2. [FAILED] Find B's price"
        prompt shouldContain "If a step is FAILED, state plainly what that leaves unknown."
        prompt shouldNotContain "Your previous answer:" // first pass — no revise section
        // The run's SYSTEM message grounds the composer too.
        composer.lastRequest!!.messages.first().role shouldBe Message.Role.SYSTEM
    }

    @Test
    fun `a revise pass includes the prior answer and the reviewer feedback`() = runTest {
        val composer = CannedComposer("Revised: only A's price (\$999) is known.")
        val revised = state(
            extraMessages = listOf(
                Message(Message.Role.ASSISTANT, "A is cheaper than B."),
                Message(
                    Message.Role.USER,
                    "Please revise your previous answer. Reviewer feedback: (a) B's price was never found.",
                ),
            ),
            scratch = mapOf(ReflectNode.VERDICT to ReflectNode.REVISE, ReflectNode.COUNT to "1"),
        )

        val out = SynthesizeNode().run(ctx(composer), revised)

        val prompt = composer.lastRequest!!.messages.last().content
        prompt shouldContain "Your previous answer:\nA is cheaper than B."
        prompt shouldContain "Reviewer feedback — revise accordingly:"
        prompt shouldContain "(a) B's price was never found."
        out.messages.last().content shouldBe "Revised: only A's price (\$999) is known."
    }
}
