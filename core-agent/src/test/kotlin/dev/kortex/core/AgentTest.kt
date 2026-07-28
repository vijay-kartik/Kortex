package dev.kortex.core

import dev.kortex.core.graph.AgentContext
import dev.kortex.core.llm.LlmChunk
import dev.kortex.core.llm.LlmProvider
import dev.kortex.core.llm.LlmRequest
import dev.kortex.core.llm.LlmResponse
import dev.kortex.core.state.Message
import dev.kortex.core.tool.RiskLevel
import dev.kortex.core.tool.ToolGovernor
import dev.kortex.core.tool.ToolRegistry
import dev.kortex.core.tool.ToolResult
import dev.kortex.core.tool.tool
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Test

/**
 * Scripted provider: returns a tool call the first time, then a final answer.
 * Lets us test the whole router -> ReAct -> tool -> governor loop with no network.
 */
private class ScriptedProvider : LlmProvider {
    private var step = 0
    override suspend fun complete(req: LlmRequest, logger: dev.kortex.core.log.Logger?): LlmResponse {
        // Router classification call has no tools attached; just label it.
        if (req.tools.isEmpty()) return LlmResponse(Message(Message.Role.ASSISTANT, "tool_task"))
        return if (step++ == 0) {
            LlmResponse(
                Message(
                    Message.Role.ASSISTANT, "",
                    toolCalls = listOf(
                        dev.kortex.core.state.ToolCall("c1", "echo", """{"text":"hi"}""")
                    )
                )
            )
        } else {
            LlmResponse(Message(Message.Role.ASSISTANT, "Done: hi"))
        }
    }
    override fun stream(req: LlmRequest): Flow<LlmChunk> = flowOf(LlmChunk.Done)
}

/**
 * Scripted provider for the plan route (T4.2): router → "plan", planner → a 2-step JSON
 * array, each execute pass → one tool call then a step answer, synthesize → the final
 * composed answer. Requests are told apart the same way the real nodes differ: router and
 * planner and synthesizer attach no tools (their prompts differ), execute passes do.
 */
private class PlanScriptedProvider(private val planReply: String) : LlmProvider {
    val toolLessPrompts = mutableListOf<String>()
    private var execStep = 0
    override suspend fun complete(req: LlmRequest, logger: dev.kortex.core.log.Logger?): LlmResponse {
        if (req.tools.isEmpty()) {
            val prompt = req.messages.last().content
            toolLessPrompts.add(prompt)
            return when {
                prompt.startsWith("Classify") -> LlmResponse(Message(Message.Role.ASSISTANT, "plan"))
                prompt.startsWith("Decompose") -> LlmResponse(Message(Message.Role.ASSISTANT, planReply))
                prompt.startsWith("Compose the final answer") ->
                    LlmResponse(Message(Message.Role.ASSISTANT, "B is cheaper: \$899 vs \$999."))
                else -> error("unexpected tool-less request: $prompt")
            }
        }
        // Execute passes: one tool call, then the step's answer.
        return if (execStep++ % 2 == 0) {
            LlmResponse(
                Message(
                    Message.Role.ASSISTANT, "",
                    toolCalls = listOf(
                        dev.kortex.core.state.ToolCall("c$execStep", "echo", """{"text":"price"}""")
                    ),
                )
            )
        } else {
            LlmResponse(Message(Message.Role.ASSISTANT, "Step answer ${execStep / 2}: \$${if (execStep < 4) 999 else 899}"))
        }
    }
    override fun stream(req: LlmRequest): Flow<LlmChunk> = flowOf(LlmChunk.Done)
}

class AgentTest {

    private fun echoTool() = tool("echo", "Echo text back") {
        param("text", "string", "text to echo")
        risk(RiskLevel.LOW)
        execute { args -> ToolResult(true, (args["text"]?.toString() ?: "")) }
    }

    @Test
    fun `routes, calls a tool through the governor, and finishes`() = runTest {
        val echo = tool("echo", "Echo text back") {
            param("text", "string", "text to echo")
            risk(RiskLevel.LOW)
            execute { args -> ToolResult(true, (args["text"]?.toString() ?: "")) }
        }
        val ctx = AgentContext(
            llm = ScriptedProvider(),
            tools = ToolRegistry(listOf(echo)),
            governor = ToolGovernor(),
        )

        val result = Agent(ctx).ask("please echo hi")

        result.done shouldBe true
        result.messages.last().content shouldContain "Done"
        result.trace.any { it.kind == "route" } shouldBe true
        result.trace.any { it.kind == "tool" } shouldBe true
    }

    // T4.2 (pattern 6): plan route end-to-end — plan → execute (self-loop, one step per
    // pass, scoped contexts) → synthesize → reflect → END, with canned completions.
    @Test
    fun `plan route decomposes, executes each step scoped, and synthesizes the answer`() = runTest {
        val provider = PlanScriptedProvider("""["Find A's price", "Find B's price"]""")
        val ctx = AgentContext(
            llm = provider,
            tools = ToolRegistry(listOf(echoTool())),
            governor = ToolGovernor(),
        )

        val result = Agent(ctx).ask("Compare A and B on price and recommend one")

        result.done shouldBe true
        result.messages.last().content shouldBe "B is cheaper: \$899 vs \$999."
        result.plan!!.steps.map { it.status } shouldBe
            List(2) { dev.kortex.core.state.PlanStep.Status.DONE }
        result.plan!!.nextPending shouldBe null

        // Scoped tool exchanges never leak into the transcript.
        result.messages.none { it.role == Message.Role.TOOL } shouldBe true

        // Trace tells the whole story: routed → planned → 2 steps → synthesized.
        result.trace.any { it.node == "plan" && it.kind == "created" && it.detail == "2 steps" } shouldBe true
        result.trace.filter { it.node == "execute" }.map { it.detail } shouldBe listOf("1:done", "2:done")
        result.trace.any { it.node == "synthesize" } shouldBe true
        result.scratch.containsKey("plan_evidence") shouldBe true

        // Step 2's scoped context saw step 1's result (the point of planning).
        // (Prompt-level detail is covered in ExecuteStepNodeTest; here we just confirm
        // the synthesize prompt carried both step results.)
        val synthPrompt = provider.toolLessPrompts.last()
        synthPrompt shouldContain "Step answer 1: \$999"
        synthPrompt shouldContain "Step answer 2: \$899"
    }

    // T4.2 degradation: a garbage plan must never make the agent worse — the graph falls
    // through to plain react, which still answers.
    @Test
    fun `garbage plan degrades to react which still answers`() = runTest {
        val provider = object : LlmProvider {
            override suspend fun complete(req: LlmRequest, logger: dev.kortex.core.log.Logger?): LlmResponse {
                if (req.tools.isEmpty()) {
                    val prompt = req.messages.last().content
                    return when {
                        prompt.startsWith("Classify") -> LlmResponse(Message(Message.Role.ASSISTANT, "plan"))
                        prompt.startsWith("Decompose") ->
                            LlmResponse(Message(Message.Role.ASSISTANT, "Sorry, I cannot produce a plan."))
                        else -> error("unexpected tool-less request: $prompt")
                    }
                }
                // React path answers directly with no tool call.
                return LlmResponse(Message(Message.Role.ASSISTANT, "Answered without a plan."))
            }
            override fun stream(req: LlmRequest): Flow<LlmChunk> = flowOf(LlmChunk.Done)
        }
        val ctx = AgentContext(
            llm = provider,
            tools = ToolRegistry(listOf(echoTool())),
            governor = ToolGovernor(),
        )

        val result = Agent(ctx).ask("Compare A and B on price and recommend one")

        result.done shouldBe true
        result.plan shouldBe null
        result.trace.any { it.node == "plan" && it.kind == "degraded" } shouldBe true
        result.messages.last().content shouldBe "Answered without a plan."
    }
}
