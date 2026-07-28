package dev.kortex.core.prompt

import dev.kortex.core.state.Message
import dev.kortex.core.state.ToolCall
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Unit tests for the T2.1 helpers in [ReflectPrompt]: pairing tool calls with their
 * TOOL-message results, per-result word-boundary truncation, and the total-section cap
 * that keeps the newest exchanges. Full-prompt snapshots live in [PromptSnapshotTest].
 */
class ReflectPromptTest {

    // --- pair(messages) ---

    @Test
    fun `pair matches each tool call to its TOOL message by toolCallId`() {
        val messages = listOf(
            Message(Message.Role.USER, "weather in Pune?"),
            Message(
                Message.Role.ASSISTANT, "",
                toolCalls = listOf(ToolCall("c1", "web_search", "{\"query\":\"pune weather\"}")),
            ),
            Message(Message.Role.TOOL, "28°C, clear.", toolCallId = "c1"),
        )
        ReflectPrompt.pair(messages) shouldBe listOf(
            ToolExchange("web_search", "{\"query\":\"pune weather\"}", "28°C, clear."),
        )
    }

    @Test
    fun `pair marks calls with no TOOL message as no result recorded`() {
        val messages = listOf(
            Message(
                Message.Role.ASSISTANT, "",
                toolCalls = listOf(ToolCall("c1", "web_search", "{}")),
            ),
            // No TOOL message for c1 (e.g. run aborted mid-turn).
        )
        ReflectPrompt.pair(messages) shouldBe listOf(
            ToolExchange("web_search", "{}", ReflectPrompt.NO_RESULT),
        )
    }

    @Test
    fun `pair preserves call order across turns and parallel calls`() {
        val messages = listOf(
            Message(
                Message.Role.ASSISTANT, "",
                toolCalls = listOf(
                    ToolCall("c1", "web_search", "{\"q\":\"a\"}"),
                    ToolCall("c2", "web_search", "{\"q\":\"b\"}"),
                ),
            ),
            // Results recorded out of call order.
            Message(Message.Role.TOOL, "result-b", toolCallId = "c2"),
            Message(Message.Role.TOOL, "result-a", toolCallId = "c1"),
            Message(
                Message.Role.ASSISTANT, "",
                toolCalls = listOf(ToolCall("c3", "open_url", "{\"url\":\"u\"}")),
            ),
            Message(Message.Role.TOOL, "result-c", toolCallId = "c3"),
        )
        ReflectPrompt.pair(messages) shouldBe listOf(
            ToolExchange("web_search", "{\"q\":\"a\"}", "result-a"),
            ToolExchange("web_search", "{\"q\":\"b\"}", "result-b"),
            ToolExchange("open_url", "{\"url\":\"u\"}", "result-c"),
        )
    }

    // --- truncateAtWord ---

    @Test
    fun `truncateAtWord leaves short text unchanged`() {
        ReflectPrompt.truncateAtWord("short result", 500) shouldBe "short result"
    }

    @Test
    fun `truncateAtWord cuts at a word boundary and appends an ellipsis`() {
        ReflectPrompt.truncateAtWord("alpha beta gamma delta", 16) shouldBe "alpha beta…"
    }

    @Test
    fun `truncateAtWord hard-cuts a single unbroken token`() {
        ReflectPrompt.truncateAtWord("x".repeat(600), 10) shouldBe "x".repeat(10) + "…"
    }

    // --- renderToolSection ---

    @Test
    fun `renderToolSection with no exchanges renders (none)`() {
        ReflectPrompt.renderToolSection(emptyList()) shouldBe "(none)"
    }

    @Test
    fun `renderToolSection pairs call and result on adjacent lines`() {
        val section = ReflectPrompt.renderToolSection(
            listOf(ToolExchange("clock", "{}", "2026-07-15T10:00")),
        )
        section shouldBe "- clock({})\n  Result: 2026-07-15T10:00"
    }

    @Test
    fun `renderToolSection total cap drops the oldest exchanges first`() {
        val exchanges = listOf(
            ToolExchange("oldest", "{}", "r1"),
            ToolExchange("middle", "{}", "r2"),
            ToolExchange("newest", "{}", "r3"),
        )
        // Each entry is "- name({})\n  Result: rN" = 25 chars; 51 fits two entries + joiner.
        val section = ReflectPrompt.renderToolSection(exchanges, totalChars = 51)
        section shouldBe listOf(
            "(1 older tool call(s) omitted)",
            "- middle({})",
            "  Result: r2",
            "- newest({})",
            "  Result: r3",
        ).joinToString("\n")
    }

    @Test
    fun `renderToolSection always keeps the newest exchange even over budget`() {
        val exchanges = listOf(
            ToolExchange("old", "{}", "r1"),
            ToolExchange("new", "{}", "r2"),
        )
        val section = ReflectPrompt.renderToolSection(exchanges, totalChars = 5)
        section shouldBe "(1 older tool call(s) omitted)\n- new({})\n  Result: r2"
    }
}
