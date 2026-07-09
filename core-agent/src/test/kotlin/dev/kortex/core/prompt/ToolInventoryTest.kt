package dev.kortex.core.prompt

import dev.kortex.core.tool.ToolRegistry
import dev.kortex.core.tool.ToolResult
import dev.kortex.core.tool.tool
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

class ToolInventoryTest {

    private fun fakeTool(name: String, description: String, hint: String? = null) =
        tool(name, description) {
            hint?.let { promptHint(it) }
            execute { ToolResult(true, "") }
        }

    @Test
    fun `renders a header plus one bullet per tool`() {
        val tools = listOf(
            fakeTool("current_time", "Get the current date and time for a timezone."),
            fakeTool("calculator", "Evaluate an arithmetic expression."),
        )
        ToolInventory.render(tools) shouldBe listOf(
            "Tools available to you:",
            "- current_time: Get the current date and time for a timezone.",
            "- calculator: Evaluate an arithmetic expression.",
        ).joinToString("\n")
    }

    @Test
    fun `appends the promptHint to that tool's bullet only`() {
        val tools = listOf(
            fakeTool("web_search", "Search the web.", hint = "Follow up with open_url for full pages."),
            fakeTool("calculator", "Evaluate an arithmetic expression."),
        )
        ToolInventory.render(tools) shouldBe listOf(
            "Tools available to you:",
            "- web_search: Search the web. Follow up with open_url for full pages.",
            "- calculator: Evaluate an arithmetic expression.",
        ).joinToString("\n")
    }

    @Test
    fun `truncates long descriptions at a word boundary`() {
        // 60 + 60 chars with a space between words — must cut at the boundary, not mid-word.
        val longDescription =
            "Sends a fully formatted multi-part message to any contact on any connected " +
                "messaging platform including WhatsApp, Telegram, and plain SMS with delivery receipts."
        val rendered = ToolInventory.render(listOf(fakeTool("send_message", longDescription)))

        val bullet = rendered.lines().last()
        bullet shouldEndWith "…"
        val body = bullet.removePrefix("- send_message: ").removeSuffix("…")
        (body.length <= ToolInventory.MAX_DESCRIPTION_CHARS) shouldBe true
        // The word straddling the 100-char cut is dropped entirely, never split.
        longDescription.startsWith(body + " ") shouldBe true
        rendered shouldNotContain "delivery receipts"
    }

    @Test
    fun `short descriptions are kept whole with no ellipsis`() {
        val rendered = ToolInventory.render(listOf(fakeTool("t", "Short and sweet.")))
        rendered shouldBe "Tools available to you:\n- t: Short and sweet."
    }

    @Test
    fun `multi-line descriptions collapse to a single bullet line`() {
        val rendered = ToolInventory.render(listOf(fakeTool("t", "Line one.\nLine   two.")))
        rendered shouldBe "Tools available to you:\n- t: Line one. Line two."
    }

    @Test
    fun `empty registry renders an empty string`() {
        ToolInventory.render(ToolRegistry()) shouldBe ""
        ToolInventory.render(emptyList()) shouldBe ""
    }

    @Test
    fun `registry render skips disabled tools`() {
        val registry = ToolRegistry(listOf(
            fakeTool("a", "Tool A."),
            fakeTool("b", "Tool B."),
        ))
        registry.disable("a")
        ToolInventory.render(registry) shouldBe "Tools available to you:\n- b: Tool B."
    }
}
