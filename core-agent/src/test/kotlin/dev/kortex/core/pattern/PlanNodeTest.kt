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
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * PlanNode (T4.2, pattern 6): the real PlanPrompt is built, the canned planner output is
 * parsed by the real fence-stripping/first-bracket logic, and degradation (no plan set)
 * is verified for garbage and too-few-steps outputs. No network.
 */
class PlanNodeTest {

    private class CannedPlanner(private val reply: String) : LlmProvider {
        var lastRequest: LlmRequest? = null
        override suspend fun complete(req: LlmRequest, logger: Logger?): LlmResponse {
            lastRequest = req
            return LlmResponse(Message(Message.Role.ASSISTANT, reply))
        }
        override fun stream(req: LlmRequest): Flow<LlmChunk> = flowOf(LlmChunk.Done)
    }

    private fun ctx(planner: CannedPlanner) = AgentContext(
        llm = planner,
        tools = ToolRegistry(),
        governor = ToolGovernor(),
    )

    private fun state(goal: String = "Compare A and B, then recommend one") = AgentState(
        messages = listOf(
            Message(Message.Role.SYSTEM, "You are Kortex."),
            Message(Message.Role.USER, goal),
        ),
        goal = Goal(goal),
    )

    @Test
    fun `clean JSON array becomes a plan of PENDING steps`() = runTest {
        val planner = CannedPlanner("""["Research A", "Research B", "Compare and recommend"]""")
        val out = PlanNode().run(ctx(planner), state())

        out.plan.shouldNotBeNull()
        out.plan!!.steps.map { it.description } shouldBe
            listOf("Research A", "Research B", "Compare and recommend")
        out.plan!!.steps.all { it.status == PlanStep.Status.PENDING } shouldBe true
        out.trace.last().let { "${it.node}/${it.kind}/${it.detail}" } shouldBe "plan/created/3 steps"
    }

    @Test
    fun `fenced JSON array parses via the fence-stripping idiom`() = runTest {
        val planner = CannedPlanner("```json\n[\"Step one\", \"Step two\"]\n```")
        val out = PlanNode().run(ctx(planner), state())

        out.plan.shouldNotBeNull()
        out.plan!!.steps.map { it.description } shouldBe listOf("Step one", "Step two")
    }

    @Test
    fun `garbage output degrades - no plan, degraded trace`() = runTest {
        val planner = CannedPlanner("I think we should first research A and then B.")
        val out = PlanNode().run(ctx(planner), state())

        out.plan.shouldBeNull()
        out.trace.last().let { "${it.node}/${it.kind}/${it.detail}" } shouldBe "plan/degraded/unparseable"
    }

    @Test
    fun `fewer than 2 steps degrades - no plan`() = runTest {
        val planner = CannedPlanner("""["Do everything at once"]""")
        val out = PlanNode().run(ctx(planner), state())

        out.plan.shouldBeNull()
        out.trace.last().detail shouldBe "too_few_steps"
    }

    @Test
    fun `more than 5 steps is clamped to 5`() = runTest {
        val planner = CannedPlanner("""["s1","s2","s3","s4","s5","s6","s7"]""")
        val out = PlanNode().run(ctx(planner), state())

        out.plan!!.steps.size shouldBe 5
        out.trace.last().detail shouldBe "5 steps"
    }

    @Test
    fun `plan nextPending walks PENDING steps and goes null when none remain`() {
        val plan = Plan(
            listOf(
                PlanStep("a", PlanStep.Status.DONE, "done a"),
                PlanStep("b", PlanStep.Status.FAILED, "boom"),
                PlanStep("c"),
            )
        )
        plan.nextPending shouldBe 2
        Plan(listOf(PlanStep("a"))).nextPending shouldBe 0
        Plan(
            listOf(
                PlanStep("a", PlanStep.Status.DONE),
                PlanStep("b", PlanStep.Status.FAILED),
            )
        ).nextPending.shouldBeNull()
    }
}
