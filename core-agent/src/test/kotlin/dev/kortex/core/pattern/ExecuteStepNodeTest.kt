package dev.kortex.core.pattern

import dev.kortex.core.graph.AgentContext
import dev.kortex.core.llm.LlmChunk
import dev.kortex.core.llm.LlmProvider
import dev.kortex.core.llm.LlmRequest
import dev.kortex.core.llm.LlmResponse
import dev.kortex.core.log.Logger
import dev.kortex.core.state.AgentState
import dev.kortex.core.state.Budget
import dev.kortex.core.state.Goal
import dev.kortex.core.state.Message
import dev.kortex.core.state.Plan
import dev.kortex.core.state.PlanStep
import dev.kortex.core.state.ToolCall
import dev.kortex.core.tool.RiskLevel
import dev.kortex.core.tool.ToolGovernor
import dev.kortex.core.tool.ToolRegistry
import dev.kortex.core.tool.ToolResult
import dev.kortex.core.tool.tool
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldEndWith
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * ExecuteStepNode (T4.2, pattern 6) with a canned LLM + real governor/registry: one
 * PENDING step per pass, scoped ephemeral context (never leaked into state.messages),
 * ReAct-style inner tool loop capped at 4 iterations, FAILED steps that don't abort the
 * plan, and shared-budget updates. No network.
 */
class ExecuteStepNodeTest {

    /** Replays [replies] in order; repeats the last one if called more often. */
    private class ScriptedLlm(private val replies: List<Message>) : LlmProvider {
        val requests = mutableListOf<LlmRequest>()
        override suspend fun complete(req: LlmRequest, logger: Logger?): LlmResponse {
            requests.add(req)
            return LlmResponse(replies[(requests.size - 1).coerceAtMost(replies.size - 1)])
        }
        override fun stream(req: LlmRequest): Flow<LlmChunk> = flowOf(LlmChunk.Done)
    }

    private fun toolCallTurn(id: String = "c1") = Message(
        Message.Role.ASSISTANT, "",
        toolCalls = listOf(ToolCall(id, "echo", """{"text":"hi"}""")),
    )

    private val echo = tool("echo", "Echo text back") {
        param("text", "string", "text to echo")
        risk(RiskLevel.LOW)
        execute { args -> ToolResult(true, "echoed: ${args["text"]}") }
    }

    private fun ctx(llm: LlmProvider) = AgentContext(
        llm = llm,
        tools = ToolRegistry(listOf(echo)),
        governor = ToolGovernor(),
    )

    private fun state(
        steps: List<PlanStep>,
        budget: Budget = Budget(),
    ) = AgentState(
        messages = listOf(
            Message(Message.Role.SYSTEM, "You are Kortex."),
            Message(Message.Role.USER, "Compare A and B"),
        ),
        goal = Goal("Compare A and B"),
        plan = Plan(steps),
        budget = budget,
    )

    @Test
    fun `runs one step - tool call then answer - and records the result`() = runTest {
        val llm = ScriptedLlm(listOf(toolCallTurn(), Message(Message.Role.ASSISTANT, "A costs \$999.")))
        val initial = state(listOf(PlanStep("Find A's price"), PlanStep("Find B's price")))

        val out = ExecuteStepNode().run(ctx(llm), initial)

        val step = out.plan!!.steps[0]
        step.status shouldBe PlanStep.Status.DONE
        step.result shouldBe "A costs \$999."
        out.plan!!.steps[1].status shouldBe PlanStep.Status.PENDING
        out.plan!!.nextPending shouldBe 1
        out.budget.toolCallsMade shouldBe 1
        out.trace.last().let { "${it.node}/${it.kind}/${it.detail}" } shouldBe "execute/step/1:done"
    }

    @Test
    fun `scoped messages never leak into state messages`() = runTest {
        val llm = ScriptedLlm(listOf(toolCallTurn(), Message(Message.Role.ASSISTANT, "done")))
        val initial = state(listOf(PlanStep("s1"), PlanStep("s2")))

        val out = ExecuteStepNode().run(ctx(llm), initial)

        // The tool exchange happened (see requests) but state.messages is untouched.
        out.messages shouldBe initial.messages
        llm.requests.size shouldBe 2
        llm.requests[1].messages.any { it.role == Message.Role.TOOL } shouldBe true
    }

    @Test
    fun `scoped context is SYSTEM plus one focused USER message with prior results`() = runTest {
        val llm = ScriptedLlm(listOf(Message(Message.Role.ASSISTANT, "B costs \$899.")))
        val initial = state(
            listOf(
                PlanStep("Find A's price", PlanStep.Status.DONE, "A costs \$999"),
                PlanStep("Find B's price"),
            )
        )

        ExecuteStepNode().run(ctx(llm), initial)

        val scoped = llm.requests.single().messages
        scoped.size shouldBe 2
        scoped[0].role shouldBe Message.Role.SYSTEM
        scoped[1].role shouldBe Message.Role.USER
        scoped[1].content shouldContain "Overall goal: Compare A and B"
        scoped[1].content shouldContain "Step 1 — done: A costs \$999"
        scoped[1].content shouldContain "step 2 of 2: Find B's price"
    }

    @Test
    fun `a step that never answers is FAILED and the plan continues`() = runTest {
        // Model wants tools on every iteration — the 4-iteration cap trips.
        val llm = ScriptedLlm(listOf(toolCallTurn()))
        val initial = state(listOf(PlanStep("s1"), PlanStep("s2")))

        val out = ExecuteStepNode().run(ctx(llm), initial)

        out.plan!!.steps[0].status shouldBe PlanStep.Status.FAILED
        out.plan!!.steps[0].result shouldBe "no answer within 4 iterations"
        out.plan!!.nextPending shouldBe 1 // FAILED does not abort the plan
        llm.requests.size shouldBe 4
        out.trace.last().detail shouldBe "1:failed"

        // A later pass sees the failure in its scoped context and still runs step 2.
        val llm2 = ScriptedLlm(listOf(Message(Message.Role.ASSISTANT, "s2 done")))
        val out2 = ExecuteStepNode().run(ctx(llm2), out)
        out2.plan!!.steps[1].status shouldBe PlanStep.Status.DONE
        llm2.requests.single().messages[1].content shouldContain
            "Step 1 — failed: no answer within 4 iterations"
    }

    @Test
    fun `budget exhaustion fails the step and stops the inner loop`() = runTest {
        val llm = ScriptedLlm(listOf(toolCallTurn()))
        val initial = state(
            listOf(PlanStep("s1"), PlanStep("s2")),
            budget = Budget(maxToolCalls = 1),
        )

        val out = ExecuteStepNode().run(ctx(llm), initial)

        out.plan!!.steps[0].status shouldBe PlanStep.Status.FAILED
        out.plan!!.steps[0].result shouldBe "budget exhausted before the step finished"
        out.budget.toolCallsMade shouldBe 1
        llm.requests.size shouldBe 1 // no second iteration once the budget is gone
    }

    @Test
    fun `unknown tool gets a failure observation like ReActNode`() = runTest {
        val llm = ScriptedLlm(
            listOf(
                Message(
                    Message.Role.ASSISTANT, "",
                    toolCalls = listOf(ToolCall("c1", "nope", "{}")),
                ),
                Message(Message.Role.ASSISTANT, "gave up gracefully"),
            )
        )
        val initial = state(listOf(PlanStep("s1"), PlanStep("s2")))

        val out = ExecuteStepNode().run(ctx(llm), initial)

        val toolMsg = llm.requests[1].messages.last { it.role == Message.Role.TOOL }
        toolMsg.content shouldBe "Unknown tool 'nope'"
        out.plan!!.steps[0].status shouldBe PlanStep.Status.DONE
        out.budget.toolCallsMade shouldBe 1
    }

    @Test
    fun `long step answers are truncated at a word boundary to about 800 chars`() = runTest {
        val long = ("word ").repeat(300).trim() // 1499 chars
        val llm = ScriptedLlm(listOf(Message(Message.Role.ASSISTANT, long)))
        val initial = state(listOf(PlanStep("s1"), PlanStep("s2")))

        val out = ExecuteStepNode().run(ctx(llm), initial)

        val result = out.plan!!.steps[0].result
        result.length shouldBe 800 // 799 kept at the word boundary + "…"
        result shouldEndWith "word…"
    }
}
