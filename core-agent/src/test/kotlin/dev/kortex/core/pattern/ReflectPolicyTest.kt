package dev.kortex.core.pattern

import dev.kortex.core.state.AgentState
import dev.kortex.core.state.Message
import dev.kortex.core.state.TraceEvent
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * [ReflectPolicy] decides when a run is trivial enough to skip the reflection review
 * (pattern 16: Resource-Aware Optimization). Skip requires ALL of: at most one tool
 * call and all successful, no prior revision, and a short, confident, non-blank answer.
 */
class ReflectPolicyTest {

    private fun toolTrace(vararg results: Pair<String, Boolean>): List<TraceEvent> =
        results.map { (name, ok) -> TraceEvent("react", "tool", "$name->$ok", 0L) }

    private fun state(
        answer: String? = "Paris is the capital of France.",
        trace: List<TraceEvent> = toolTrace("web_search" to true),
        scratch: Map<String, String> = emptyMap(),
    ): AgentState = AgentState(
        messages = listOfNotNull(
            Message(Message.Role.USER, "What is the capital of France?"),
            answer?.let { Message(Message.Role.ASSISTANT, it) },
        ),
        scratch = scratch,
        trace = trace,
    )

    @Test
    fun `single successful tool call with short answer skips review`() {
        ReflectPolicy.shouldSkip(state()) shouldBe true
        ReflectPolicy.shouldReflect(state()) shouldBe false
    }

    @Test
    fun `multi-tool run reflects`() {
        val s = state(trace = toolTrace("web_search" to true, "web_fetch" to true))
        ReflectPolicy.shouldReflect(s) shouldBe true
    }

    @Test
    fun `failed tool call reflects`() {
        val s = state(trace = toolTrace("web_search" to false))
        ReflectPolicy.shouldReflect(s) shouldBe true
    }

    @Test
    fun `long answer reflects`() {
        val s = state(answer = "x".repeat(600))
        ReflectPolicy.shouldReflect(s) shouldBe true
    }

    @Test
    fun `hedging answer reflects`() {
        listOf(
            "I couldn't find the population figure.",
            "I could not verify this.",
            "I'm NOT SURE about the exact date.",
            "I was unable to open that page.",
            "The search returned no results.",
        ).forEach { answer ->
            ReflectPolicy.shouldReflect(state(answer = answer)) shouldBe true
        }
    }

    @Test
    fun `second pass reflects even for trivial answers`() {
        val s = state(scratch = mapOf(ReflectNode.COUNT to "1"))
        ReflectPolicy.shouldReflect(s) shouldBe true
    }

    @Test
    fun `blank answer reflects (defers to ReflectNode's own guard)`() {
        ReflectPolicy.shouldReflect(state(answer = null)) shouldBe true
    }

    // A run with zero tool calls skips: there are no tool results to verify the answer
    // against, so the reviewer could only re-judge fluency — not worth a REASONING call.
    // The run only went through react (rather than direct answer) because the router
    // sent it there.
    @Test
    fun `no-tool react answer skips review`() {
        val s = state(trace = emptyList())
        ReflectPolicy.shouldSkip(s) shouldBe true
    }

    @Test
    fun `non-tool trace events are ignored`() {
        val s = state(
            trace = listOf(TraceEvent("react", "llm", "tools=1", 0L)) +
                toolTrace("clock" to true) +
                listOf(TraceEvent("router", "route", "tool_task", 0L)),
        )
        ReflectPolicy.shouldSkip(s) shouldBe true
    }
}
