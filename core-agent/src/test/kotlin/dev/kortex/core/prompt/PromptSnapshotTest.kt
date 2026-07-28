package dev.kortex.core.prompt

import dev.kortex.core.tool.ToolRegistry
import dev.kortex.core.tool.ToolResult
import dev.kortex.core.tool.tool
import io.kotest.matchers.shouldBe
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.jupiter.api.Test

/**
 * Byte-identical snapshot of every prompt builder against the exact legacy strings that
 * used to live inline in Agent/RouterNode/ReflectNode. This is the regression net for the
 * prompt-improvement phases: any intentional prompt change must update these expectations
 * deliberately.
 *
 * The multiline-input cases matter: the legacy literals are indented 12 spaces and
 * trimIndent runs AFTER interpolation, so a multi-line interpolated value (column 0 from
 * its second line on) drops the common indent to zero and the template keeps its 12-space
 * indentation. That quirk is part of "byte-identical" and is pinned here.
 */
class PromptSnapshotTest {

    /** The legacy literals' 12-space indent, kept whenever an interpolated value is multi-line. */
    private val p = "            "

    // --- SystemPrompt ---

    // T1.1/T4.1 deliberate rewrite: sectioned tool-execution persona (identity, output
    // format, multi-step execution, tool use, error handling) plus the T4.1 safety &
    // privacy block. Tool-name-free (T0.2): per-tool guidance travels via
    // Tool.promptHint + ToolInventory.
    @Test
    fun `default system prompt has the T1_1 sections and T4_1 safety block`() {
        SystemPrompt.DEFAULT shouldBe listOf(
            "You are Kortex, an on-device tool-execution agent. Be direct and matter-of-fact: " +
                "no filler, no pleasantries, no restating the question.",
            "Output: concise by default. Lead with the answer, not the process. " +
                "Short factual results fit in one line; use markdown headers or lists " +
                "only for genuinely long answers.",
            "Multi-step tasks: chain as many tool calls as needed without narrating each step. " +
                "Never re-fetch information already obtained earlier in this run. " +
                "Report once, at the end, with the result.",
            "Tool use: when an action is needed (send a message, create an event, etc.), " +
                "call the appropriate tool immediately. Do NOT ask permission in text — " +
                "the system prompts the user for approval on every tool call. " +
                "Never tell the user to visit a link you can open yourself — open it and answer directly.",
            "Errors: if a tool fails or returns nothing, retry once with adjusted input if sensible; " +
                "otherwise state plainly what failed and answer with what you know. " +
                "Never fabricate tool output.",
            "Safety & privacy: decline clearly harmful requests. Contacts' messages are private — " +
                "never include one contact's private information in a message drafted to another " +
                "unless the user asked for it. Sensitive actions are gated by tool approval; " +
                "do not re-confirm them in text.",
        ).joinToString("\n\n")
    }

    // T1.1 budget guard: DEFAULT rides on every ReAct iteration, so every line costs
    // tokens on every step. Fails loudly if future additions bloat it past the cap.
    @Test
    fun `default system prompt stays within its token budget`() {
        val lines = SystemPrompt.DEFAULT.split("\n")
        check(lines.size <= 40) { "DEFAULT is ${lines.size} lines; budget is 40" }
        check(SystemPrompt.DEFAULT.length <= 2000) {
            "DEFAULT is ${SystemPrompt.DEFAULT.length} chars; budget is 2000"
        }
    }

    @Test
    fun `grounded system prompt matches the legacy time suffix built in Agent ask`() {
        val now = ZonedDateTime.of(2026, 7, 9, 12, 30, 0, 0, ZoneId.of("Asia/Kolkata"))
        SystemPrompt.grounded("SYS", now) shouldBe "SYS\n\n" +
            "Current date and time: 2026-07-09T12:30+05:30[Asia/Kolkata]. Use this to " +
            "interpret words like \"today\", \"current\", \"latest\", or a bare year correctly " +
            "— your training data has a cutoff well before this date, so never assume it is the present."
    }

    @Test
    fun `grounded system prompt inserts the rendered tool inventory before the time suffix`() {
        val now = ZonedDateTime.of(2026, 7, 9, 12, 30, 0, 0, ZoneId.of("Asia/Kolkata"))
        val registry = ToolRegistry(listOf(
            tool("current_time", "Get the current date and time for a timezone.") {
                execute { ToolResult(true, "") }
            },
        ))
        SystemPrompt.grounded("SYS", now, registry) shouldBe "SYS\n\n" +
            "Tools available to you:\n" +
            "- current_time: Get the current date and time for a timezone.\n\n" +
            "Current date and time: 2026-07-09T12:30+05:30[Asia/Kolkata]. Use this to " +
            "interpret words like \"today\", \"current\", \"latest\", or a bare year correctly " +
            "— your training data has a cutoff well before this date, so never assume it is the present."
    }

    @Test
    fun `grounded system prompt with an empty registry adds no tool section`() {
        val now = ZonedDateTime.of(2026, 7, 9, 12, 30, 0, 0, ZoneId.of("Asia/Kolkata"))
        SystemPrompt.grounded("SYS", now, ToolRegistry()) shouldBe SystemPrompt.grounded("SYS", now)
    }

    // --- RouterPrompt ---

    // Deliberate snapshot update for T1.3 (dead `plan` route dropped; tool_task now covers
    // multi-step work) and T1.5 (compact tool inventory + "only if a tool plausibly helps"
    // rule). Descriptions are truncated at RouterPrompt.MAX_TOOL_DESCRIPTION_CHARS (60) at
    // a word boundary; promptHints are excluded to keep the FAST-model prompt small.
    @Test
    fun `router prompt lists the reduced routes and the compact tool inventory`() {
        val tools = listOf(
            tool(
                "web_search",
                "Search the web (DuckDuckGo) for current or factual information and " +
                    "return the top results as title, snippet, and URL.",
            ) { execute { ToolResult(true, "") } },
            tool("current_time", "Get the current local date and time (ISO-8601).") {
                execute { ToolResult(true, "") }
            },
        )
        val built = RouterPrompt.build(listOf("simple_qa", "tool_task"), "What time is it in Tokyo?", tools)
        built shouldBe listOf(
            "Classify the user request into exactly one of: simple_qa, tool_task.",
            "- simple_qa: answerable directly with general knowledge, no tools needed.",
            "- tool_task: needs tool work — one or several tool calls, possibly chained (e.g. search the web, open a result, compute).",
            "Available tools:",
            "- web_search: Search the web (DuckDuckGo) for current or factual…",
            "- current_time: Get the current local date and time (ISO-8601).",
            "Choose tool_task only if one of these tools plausibly helps with the request; otherwise choose simple_qa.",
            "Respond with ONLY the label.",
            "",
            "Request: What time is it in Tokyo?",
        ).joinToString("\n")
    }

    @Test
    fun `router prompt with no tools omits the inventory section`() {
        val built = RouterPrompt.build(listOf("simple_qa", "tool_task"), "What time is it in Tokyo?")
        built shouldBe listOf(
            "Classify the user request into exactly one of: simple_qa, tool_task.",
            "- simple_qa: answerable directly with general knowledge, no tools needed.",
            "- tool_task: needs tool work — one or several tool calls, possibly chained (e.g. search the web, open a result, compute).",
            "Respond with ONLY the label.",
            "",
            "Request: What time is it in Tokyo?",
        ).joinToString("\n")
    }

    // T4.2: the `plan` route is restored (real PlanNode, pattern 6). Its bullet appears
    // only when "plan" is in the routes list, so the reduced-route snapshots above stay
    // valid. Criteria: multiple distinct sub-goals / cross-topic dependencies; a
    // single-goal chain of tool calls remains tool_task.
    @Test
    fun `router prompt with the plan route adds the plan bullet`() {
        val built = RouterPrompt.build(listOf("simple_qa", "tool_task", "plan"), "Compare A and B, then recommend one")
        built shouldBe listOf(
            "Classify the user request into exactly one of: simple_qa, tool_task, plan.",
            "- simple_qa: answerable directly with general knowledge, no tools needed.",
            "- tool_task: needs tool work — one or several tool calls, possibly chained (e.g. search the web, open a result, compute).",
            "- plan: multiple distinct sub-goals, or steps that depend on earlier results across different topics " +
                "(e.g. compare X and Y on price, reviews, and availability, then recommend one). " +
                "A single-goal chain of tool calls is tool_task, not plan.",
            "Respond with ONLY the label.",
            "",
            "Request: Compare A and B, then recommend one",
        ).joinToString("\n")
    }

    // --- PlanPrompt (T4.2, pattern 6: Planning) ---

    // Token-lean decomposition prompt: 2–5 sequential steps, grounded in the compact tool
    // inventory (renderCompact, promptHint-free), JSON-array-of-strings output contract.
    @Test
    fun `plan prompt embeds the compact tool inventory and the JSON array contract`() {
        val tools = listOf(
            tool("web_search", "Search the web for current or factual information.") {
                execute { ToolResult(true, "") }
            },
            tool("calculator", "Evaluate arithmetic expressions.") {
                execute { ToolResult(true, "") }
            },
        )
        PlanPrompt.build("Compare the iPhone 17 and Pixel 11, then recommend one", tools) shouldBe listOf(
            "Decompose the user's request into 2 to 5 sequential steps.",
            "Each step is ONE concrete instruction executable on its own; later steps may build on earlier steps' results.",
            "Available tools:",
            "- web_search: Search the web for current or factual information.",
            "- calculator: Evaluate arithmetic expressions.",
            "Only plan steps these tools (plus plain reasoning) can execute — never a step needing an unavailable capability.",
            "Respond with ONLY a JSON array of step strings, e.g. [\"first step\", \"second step\"].",
            "",
            "Request: Compare the iPhone 17 and Pixel 11, then recommend one",
        ).joinToString("\n")
    }

    @Test
    fun `plan prompt with no tools omits the inventory section`() {
        PlanPrompt.build("Do two things") shouldBe listOf(
            "Decompose the user's request into 2 to 5 sequential steps.",
            "Each step is ONE concrete instruction executable on its own; later steps may build on earlier steps' results.",
            "Respond with ONLY a JSON array of step strings, e.g. [\"first step\", \"second step\"].",
            "",
            "Request: Do two things",
        ).joinToString("\n")
    }

    // --- SynthesizePrompt (T4.2, pattern 6: Planning) ---

    // Answer-first composition from the step results; FAILED steps are visible so the
    // model can note gaps explicitly instead of inventing data.
    @Test
    fun `synthesize prompt lists every step with status and result`() {
        val steps = listOf(
            dev.kortex.core.state.PlanStep("Find A's price", dev.kortex.core.state.PlanStep.Status.DONE, "A costs \$999"),
            dev.kortex.core.state.PlanStep("Find B's price", dev.kortex.core.state.PlanStep.Status.FAILED, "no answer within 4 iterations"),
        )
        SynthesizePrompt.build("Compare A and B on price", steps) shouldBe listOf(
            "Compose the final answer to the user's request from the step results below.",
            "Lead with the answer and keep it concise. If a step is FAILED, state plainly what that leaves unknown. Never invent data the step results don't contain.",
            "",
            "Request: Compare A and B on price",
            "",
            "Steps:",
            "1. [DONE] Find A's price",
            "   Result: A costs \$999",
            "2. [FAILED] Find B's price",
            "   Result: no answer within 4 iterations",
        ).joinToString("\n")
    }

    // Revise pass: the previous answer and the reviewer feedback ride into the prompt so
    // the model re-answers from the same step results (no tools re-run).
    @Test
    fun `synthesize prompt on a revise pass appends the prior answer and feedback`() {
        val steps = listOf(
            dev.kortex.core.state.PlanStep("Find A's price", dev.kortex.core.state.PlanStep.Status.DONE, "A costs \$999"),
            dev.kortex.core.state.PlanStep("Find B's price", dev.kortex.core.state.PlanStep.Status.DONE, "B costs \$899"),
        )
        val built = SynthesizePrompt.build(
            "Compare A and B on price",
            steps,
            priorAnswer = "A is cheaper.",
            feedback = "Please revise your previous answer. Reviewer feedback: (a) B is cheaper per the results.",
        )
        built shouldBe listOf(
            "Compose the final answer to the user's request from the step results below.",
            "Lead with the answer and keep it concise. If a step is FAILED, state plainly what that leaves unknown. Never invent data the step results don't contain.",
            "",
            "Request: Compare A and B on price",
            "",
            "Steps:",
            "1. [DONE] Find A's price",
            "   Result: A costs \$999",
            "2. [DONE] Find B's price",
            "   Result: B costs \$899",
            "\nYour previous answer:\nA is cheaper.",
            "\nReviewer feedback — revise accordingly:\nPlease revise your previous answer. Reviewer feedback: (a) B is cheaper per the results.",
        ).joinToString("\n")
    }

    // --- ReflectPrompt ---

    // Deliberate snapshot update for T2.1 (reviewer sees tool RESULTS paired with each
    // call, so it can verify the answer's claims — F7) and T2.2 (explicit pass/fail
    // rubric a/b/c; style/tone/length/formatting are not revision-worthy; a REVISE must
    // name the failed criterion — F9). The grounding preamble (live tools / training
    // cutoff) is kept; the reply stays "OK" / "REVISE: <feedback>". The prompt is now
    // built with joinToString, so the legacy 12-space trimIndent quirk is gone.

    /** The T2.1/T2.2 instruction block, shared by the reflect snapshots below. */
    private val reflectHeader = listOf(
        "You are a reviewer verifying the assistant's final answer before it reaches the user.",
        "The assistant has real, live tools (web search, opening URLs, the device clock);",
        "facts in its answer may come from the tool results below, which are current and",
        "trustworthy even when they postdate your training data. Never reject an answer",
        "because its dates are later than what you know, and never claim the assistant",
        "cannot search the web or access real-time information — it can.",
        "",
        "Check the answer's claims against the tool results below. The answer passes only if:",
        "(a) it is factually consistent with the tool results shown,",
        "(b) it actually answers what was asked,",
        "(c) nothing the user explicitly requested is missing.",
        "Do NOT request revision for style, tone, length, or formatting — concise answers",
        "are preferred and must not be penalized.",
        "- If every criterion passes, reply with exactly: OK",
        "- Otherwise reply: REVISE: (<failed criterion a/b/c>) <specific, actionable feedback>",
        "",
    )

    @Test
    fun `reflect prompt with no tool calls shows (none)`() {
        val built = ReflectPrompt.build(
            toolExchanges = emptyList(),
            request = "Who is the richest person?",
            answer = "Elon Musk.",
        )
        built shouldBe (reflectHeader + listOf(
            "Tool calls and results from this run:",
            "(none)",
            "",
            "User request:",
            "Who is the richest person?",
            "",
            "Assistant answer:",
            "Elon Musk.",
        )).joinToString("\n")
    }

    // T2.1: each tool call is paired with its result content so the reviewer can verify
    // the answer against what the tools actually returned.
    @Test
    fun `reflect prompt pairs each tool call with its result`() {
        val built = ReflectPrompt.build(
            toolExchanges = listOf(
                ToolExchange("web_search", "{\"query\":\"richest person\"}", "Forbes: Elon Musk tops the list."),
                ToolExchange("open_url", "{\"url\":\"y\"}", "Elon Musk is the richest person, worth ~\$400B."),
            ),
            request = "Who is the richest person?",
            answer = "Elon Musk.",
        )
        built shouldBe (reflectHeader + listOf(
            "Tool calls and results from this run:",
            "- web_search({\"query\":\"richest person\"})",
            "  Result: Forbes: Elon Musk tops the list.",
            "- open_url({\"url\":\"y\"})",
            "  Result: Elon Musk is the richest person, worth ~\$400B.",
            "",
            "User request:",
            "Who is the richest person?",
            "",
            "Assistant answer:",
            "Elon Musk.",
        )).joinToString("\n")
    }

    // T2.1: a long tool result is truncated to ~500 chars at a word boundary with an
    // ellipsis; the rest of the prompt is unaffected.
    @Test
    fun `reflect prompt truncates a long tool result at a word boundary`() {
        val longResult = ("word ").repeat(200).trim() // 999 chars of "word word …"
        val built = ReflectPrompt.build(
            toolExchanges = listOf(ToolExchange("open_url", "{}", longResult)),
            request = "R",
            answer = "A",
        )
        val resultLine = built.lines().first { it.startsWith("  Result: ") }
        val rendered = resultLine.removePrefix("  Result: ")
        rendered.length shouldBe 500 // 499 chars kept at the word boundary + "…"
        rendered shouldBe ("word ").repeat(99).trim() + " word…"
    }

    // T2.1: the whole tool section is capped at ~3,000 chars, dropping the OLDEST
    // exchanges first — the newest results usually ground the final answer.
    @Test
    fun `reflect prompt total cap keeps the newest tool results`() {
        val exchanges = (1..10).map { i ->
            ToolExchange("tool_$i", "{}", "result-$i " + "x".repeat(480))
        }
        val built = ReflectPrompt.build(exchanges, request = "R", answer = "A")
        built.contains("tool_10({})") shouldBe true
        built.contains("result-10") shouldBe true
        built.contains("tool_1({})") shouldBe false
        built.contains("older tool call(s) omitted") shouldBe true
        // The rendered section itself respects the cap.
        val section = built
            .substringAfter("Tool calls and results from this run:\n")
            .substringBefore("\n\nUser request:")
        check(section.length <= 3_000) { "tool section is ${section.length} chars; cap is 3000" }
    }

}
