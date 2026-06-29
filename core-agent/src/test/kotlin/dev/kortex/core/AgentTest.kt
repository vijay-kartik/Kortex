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
    override suspend fun complete(req: LlmRequest): LlmResponse {
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

class AgentTest {
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
}
