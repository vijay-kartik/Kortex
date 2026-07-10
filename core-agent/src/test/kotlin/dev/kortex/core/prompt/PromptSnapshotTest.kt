package dev.kortex.core.prompt

import dev.kortex.core.ambient.Direction
import dev.kortex.core.ambient.Handle
import dev.kortex.core.ambient.HandleType
import dev.kortex.core.ambient.Signal
import dev.kortex.core.ambient.SignalKind
import dev.kortex.core.ambient.SignalSource
import dev.kortex.core.ambient.TriageContext
import dev.kortex.core.tool.ToolRegistry
import dev.kortex.core.tool.ToolResult
import dev.kortex.core.tool.tool
import io.kotest.matchers.shouldBe
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.jupiter.api.Test

/**
 * Byte-identical snapshot of every prompt builder against the exact legacy strings that
 * used to live inline in Agent/RouterNode/ReflectNode/AmbientTriage/LlmCardGenerator/
 * LlmMemoryWriter. This is the regression net for the prompt-improvement phases: any
 * intentional prompt change must update these expectations deliberately.
 *
 * The multiline-input cases matter: the legacy literals are indented 12 spaces and
 * trimIndent runs AFTER interpolation, so a multi-line interpolated value (column 0 from
 * its second line on) drops the common indent to zero and the template keeps its 12-space
 * indentation. That quirk is part of "byte-identical" and is pinned here.
 */
class PromptSnapshotTest {

    /** The legacy literals' 12-space indent, kept whenever an interpolated value is multi-line. */
    private val p = "            "

    private fun signal(content: String, at: Long = 1_751_700_000_000) = Signal(
        id = "sig_$content",
        source = SignalSource("com.whatsapp", "WhatsApp"),
        kind = SignalKind.MESSAGE,
        direction = Direction.INCOMING,
        senderHandle = Handle(HandleType.PHONE, "+919812345678"),
        content = content,
        timestampMillis = at,
    )

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

    // --- ReflectPrompt ---

    @Test
    fun `reflect prompt matches the legacy ReflectNode prompt with no tool calls`() {
        val built = ReflectPrompt.build(toolsUsed = "", request = "Who is the richest person?", answer = "Elon Musk.")
        built shouldBe listOf(
            "You are a strict reviewer. Decide whether the assistant's answer fully and",
            "correctly addresses the user's request.",
            "The assistant has real, live tools (web search, opening URLs, the device clock);",
            "facts in its answer may come from those tool results, which are current and",
            "trustworthy even when they postdate your training data. Never reject an answer",
            "because its dates are later than what you know, and never claim the assistant",
            "cannot search the web or access real-time information — it can.",
            "- If the answer is good, reply with exactly: OK",
            "- Otherwise reply: REVISE: <specific, actionable feedback>",
            "",
            "Tool calls the assistant already made during this run:",
            "(none)",
            "",
            "User request:",
            "Who is the richest person?",
            "",
            "Assistant answer:",
            "Elon Musk.",
        ).joinToString("\n")
    }

    @Test
    fun `reflect prompt keeps the legacy 12-space indent when the tool list is multi-line`() {
        val built = ReflectPrompt.build(
            toolsUsed = "- web_search({\"query\":\"x\"})\n- open_url({\"url\":\"y\"})",
            request = "Who is the richest person?",
            answer = "Elon Musk.",
        )
        built shouldBe listOf(
            p + "You are a strict reviewer. Decide whether the assistant's answer fully and",
            p + "correctly addresses the user's request.",
            p + "The assistant has real, live tools (web search, opening URLs, the device clock);",
            p + "facts in its answer may come from those tool results, which are current and",
            p + "trustworthy even when they postdate your training data. Never reject an answer",
            p + "because its dates are later than what you know, and never claim the assistant",
            p + "cannot search the web or access real-time information — it can.",
            p + "- If the answer is good, reply with exactly: OK",
            p + "- Otherwise reply: REVISE: <specific, actionable feedback>",
            "",
            p + "Tool calls the assistant already made during this run:",
            p + "- web_search({\"query\":\"x\"})",
            "- open_url({\"url\":\"y\"})",
            "",
            p + "User request:",
            p + "Who is the richest person?",
            "",
            p + "Assistant answer:",
            p + "Elon Musk.",
        ).joinToString("\n")
    }

    // --- TriagePrompt ---

    @Test
    fun `triage prompt matches the legacy AmbientTriage prompt`() {
        val ctx = TriageContext(
            contactName = "Neha",
            newSignals = listOf(signal("Flight lands at 6, can you pick me up?")),
        )
        val built = TriagePrompt.build(
            ctx,
            activity = "- [Jul 5, 10:00] via WhatsApp: Flight lands at 6, can you pick me up?",
            memory = "(none)",
        )
        built shouldBe listOf(
            "You triage incoming communications for a personal assistant. Decide what to do",
            "about new activity from the contact \"Neha\".",
            "",
            "Choose exactly one:",
            "- GENERATE_CARD: there is something the user likely wants to see or act on now",
            "  (a question to answer, a request, a plan to confirm, a time-sensitive item).",
            "- STORE_MEMORY: useful context worth remembering, but nothing to act on now.",
            "- IGNORE: trivial, noise, or already-handled chit-chat (\"ok\", reactions, spam).",
            "",
            "When unsure, prefer STORE_MEMORY over GENERATE_CARD.",
            "",
            "Conversation summary so far:",
            "(none yet)",
            "",
            "What we already know about this contact:",
            "(none)",
            "",
            "New activity:",
            "- [Jul 5, 10:00] via WhatsApp: Flight lands at 6, can you pick me up?",
            "",
            "Respond with the label on the first line, then a short reason on the next line.",
        ).joinToString("\n")
    }

    @Test
    fun `triage prompt keeps the legacy 12-space indent when activity is multi-line`() {
        val ctx = TriageContext(
            contactName = "Neha",
            newSignals = listOf(signal("Flight lands at 6"), signal("Can you pick me up?")),
        )
        val built = TriagePrompt.build(
            ctx,
            activity = "- [Jul 5, 10:00] via WhatsApp: Flight lands at 6\n" +
                "- [Jul 5, 10:01] via WhatsApp: Can you pick me up?",
            memory = "(none)",
        )
        built shouldBe listOf(
            p + "You triage incoming communications for a personal assistant. Decide what to do",
            p + "about new activity from the contact \"Neha\".",
            "",
            p + "Choose exactly one:",
            p + "- GENERATE_CARD: there is something the user likely wants to see or act on now",
            p + "  (a question to answer, a request, a plan to confirm, a time-sensitive item).",
            p + "- STORE_MEMORY: useful context worth remembering, but nothing to act on now.",
            p + "- IGNORE: trivial, noise, or already-handled chit-chat (\"ok\", reactions, spam).",
            "",
            p + "When unsure, prefer STORE_MEMORY over GENERATE_CARD.",
            "",
            p + "Conversation summary so far:",
            p + "(none yet)",
            "",
            p + "What we already know about this contact:",
            p + "(none)",
            "",
            p + "New activity:",
            p + "- [Jul 5, 10:00] via WhatsApp: Flight lands at 6",
            "- [Jul 5, 10:01] via WhatsApp: Can you pick me up?",
            "",
            p + "Respond with the label on the first line, then a short reason on the next line.",
        ).joinToString("\n")
    }

    // --- CardPrompts ---

    @Test
    fun `card generate prompt matches the legacy LlmCardGenerator prompt without feedback`() {
        val ctx = TriageContext(
            contactName = "Neha",
            newSignals = listOf(signal("Flight lands at 6, can you pick me up?")),
        )
        CardPrompts.generate(ctx, feedback = null) shouldBe listOf(
            "You build a single actionable \"card\" for a personal assistant about the contact",
            "\"Neha\", summarizing what's been shared across all messaging apps and",
            "suggesting what the user can do next. Base everything ONLY on the activity and",
            "known facts below — never invent details.",
            "",
            "Conversation summary so far:",
            "(none yet)",
            "",
            "What we already know:",
            "(none)",
            "",
            "New activity (across mediums):",
            "- via WhatsApp: Flight lands at 6, can you pick me up?",
            "",
            "Return ONLY a JSON object:",
            "{",
            "  \"makeCard\": true,",
            "  \"title\": \"<short title>\",",
            "  \"summary\": \"<combined, medium-agnostic summary of what was shared>\",",
            "  \"priority\": \"LOW|MEDIUM|HIGH|URGENT\",",
            "  \"actions\": [",
            "    { \"type\": \"reply_text|share_location|share_media|set_reminder|schedule_checkin|create_event|call\",",
            "      \"label\": \"<button text>\", \"text\": \"<message/draft/title if relevant>\",",
            "      \"atMillis\": <epoch ms if time-based>, \"live\": false, \"mediaType\": \"IMAGE|FILE|...\",",
            "      \"startMillis\": <epoch ms for events> }",
            "  ],",
            "  \"entities\": [ { \"type\": \"PERSON|PLACE|DATE_TIME|EVENT|COMMITMENT|ORGANIZATION|TOPIC|OTHER\",",
            "                  \"name\": \"<canonical>\", \"surfaceText\": \"<as written>\" } ],",
            "  \"memories\": [ { \"content\": \"<durable fact>\", \"kind\": \"FACT|PREFERENCE|EVENT|COMMITMENT|RELATIONSHIP|OTHER\",",
            "                  \"salience\": 0.5, \"tags\": [\"...\"] } ]",
            "}",
            "Set \"makeCard\": false if, on reflection, nothing is truly card-worthy.",
        ).joinToString("\n")
    }

    @Test
    fun `card generate prompt keeps the legacy 12-space indent when reviewer feedback is present`() {
        val ctx = TriageContext(
            contactName = "Neha",
            newSignals = listOf(signal("Flight lands at 6")),
        )
        CardPrompts.generate(ctx, feedback = "Don't invent times.") shouldBe listOf(
            p + "You build a single actionable \"card\" for a personal assistant about the contact",
            p + "\"Neha\", summarizing what's been shared across all messaging apps and",
            p + "suggesting what the user can do next. Base everything ONLY on the activity and",
            p + "known facts below — never invent details.",
            p,
            "Revise your previous card using this reviewer feedback:",
            "Don't invent times.",
            "",
            p + "Conversation summary so far:",
            p + "(none yet)",
            "",
            p + "What we already know:",
            p + "(none)",
            "",
            p + "New activity (across mediums):",
            p + "- via WhatsApp: Flight lands at 6",
            "",
            p + "Return ONLY a JSON object:",
            p + "{",
            p + "  \"makeCard\": true,",
            p + "  \"title\": \"<short title>\",",
            p + "  \"summary\": \"<combined, medium-agnostic summary of what was shared>\",",
            p + "  \"priority\": \"LOW|MEDIUM|HIGH|URGENT\",",
            p + "  \"actions\": [",
            p + "    { \"type\": \"reply_text|share_location|share_media|set_reminder|schedule_checkin|create_event|call\",",
            p + "      \"label\": \"<button text>\", \"text\": \"<message/draft/title if relevant>\",",
            p + "      \"atMillis\": <epoch ms if time-based>, \"live\": false, \"mediaType\": \"IMAGE|FILE|...\",",
            p + "      \"startMillis\": <epoch ms for events> }",
            p + "  ],",
            p + "  \"entities\": [ { \"type\": \"PERSON|PLACE|DATE_TIME|EVENT|COMMITMENT|ORGANIZATION|TOPIC|OTHER\",",
            p + "                  \"name\": \"<canonical>\", \"surfaceText\": \"<as written>\" } ],",
            p + "  \"memories\": [ { \"content\": \"<durable fact>\", \"kind\": \"FACT|PREFERENCE|EVENT|COMMITMENT|RELATIONSHIP|OTHER\",",
            p + "                  \"salience\": 0.5, \"tags\": [\"...\"] } ]",
            p + "}",
            p + "Set \"makeCard\": false if, on reflection, nothing is truly card-worthy.",
        ).joinToString("\n")
    }

    @Test
    fun `card reflect prompt matches the legacy LlmCardGenerator reviewer prompt`() {
        val ctx = TriageContext(
            contactName = "Neha",
            newSignals = listOf(signal("Flight lands at 6, can you pick me up?")),
        )
        val built = CardPrompts.reflect(
            ctx,
            title = "Pick up Neha",
            summary = "Neha's flight lands at 6 and she asked for a pickup.",
            actions = "reply_text (On my way!), set_reminder",
        )
        built shouldBe listOf(
            "You are a strict reviewer of a proposed assistant card. Check that it is:",
            "- grounded ONLY in the activity below (no invented facts),",
            "- genuinely useful/actionable for the user,",
            "- appropriate (actions don't overreach or assume consent the user didn't give).",
            "",
            "Reply with exactly \"OK\" if it's good, otherwise \"REVISE: <specific feedback>\".",
            "",
            "Activity:",
            "- Flight lands at 6, can you pick me up?",
            "",
            "Proposed card:",
            "title: Pick up Neha",
            "summary: Neha's flight lands at 6 and she asked for a pickup.",
            "actions: reply_text (On my way!), set_reminder",
        ).joinToString("\n")
    }

    // --- MemoryPrompt ---

    @Test
    fun `memory prompt matches the legacy LlmMemoryWriter prompt`() {
        val ctx = TriageContext(
            contactName = "Neha",
            newSignals = listOf(signal("I got the new job!")),
            recentMemory = listOf("Neha lives in Pune"),
        )
        MemoryPrompt.build(ctx) shouldBe listOf(
            "Extract durable facts worth remembering about the contact \"Neha\"",
            "from the new activity below — things useful for future context (preferences,",
            "commitments, life events, relationships, stable facts). Do NOT include trivia,",
            "one-off chit-chat, or anything already in \"What we already know\".",
            "",
            "Return a JSON array (and nothing else). Each item:",
            "  { \"content\": \"<concise fact>\", \"kind\": \"<one of: FACT, PREFERENCE, EVENT, COMMITMENT, RELATIONSHIP, OTHER>\",",
            "    \"salience\": <0.0-1.0 importance>, \"tags\": [\"...\"] }",
            "Return [] if there is nothing new worth keeping.",
            "",
            "Conversation summary so far:",
            "(none yet)",
            "",
            "What we already know:",
            "- Neha lives in Pune",
            "",
            "New activity:",
            "- via WhatsApp: I got the new job!",
        ).joinToString("\n")
    }
}
