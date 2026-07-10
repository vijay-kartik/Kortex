package dev.kortex.core.prompt

import dev.kortex.core.tool.ToolRegistry
import java.time.ZonedDateTime

/**
 * The main agent's system prompt: the default persona/tool-use instructions, the tool
 * inventory rendered live from the registry (see [ToolInventory]), and the time-grounding
 * suffix appended by [grounded] per call.
 */
object SystemPrompt {

    // T1.1 (identity/output/multi-step/tool-use/errors) + T4.1 (safety & privacy).
    // Budget: this string rides on EVERY ReAct iteration — keep it well under 40 lines
    // (enforced by PromptSnapshotTest) and be ruthless about wording economy.
    const val DEFAULT =
        "You are Kortex, an on-device tool-execution agent. Be direct and matter-of-fact: " +
            "no filler, no pleasantries, no restating the question.\n\n" +
            "Output: concise by default. Lead with the answer, not the process. " +
            "Short factual results fit in one line; use markdown headers or lists " +
            "only for genuinely long answers.\n\n" +
            "Multi-step tasks: chain as many tool calls as needed without narrating each step. " +
            "Never re-fetch information already obtained earlier in this run. " +
            "Report once, at the end, with the result.\n\n" +
            "Tool use: when an action is needed (send a message, create an event, etc.), " +
            "call the appropriate tool immediately. Do NOT ask permission in text — " +
            "the system prompts the user for approval on every tool call. " +
            "Never tell the user to visit a link you can open yourself — open it and answer directly.\n\n" +
            "Errors: if a tool fails or returns nothing, retry once with adjusted input if sensible; " +
            "otherwise state plainly what failed and answer with what you know. " +
            "Never fabricate tool output.\n\n" +
            "Safety & privacy: decline clearly harmful requests. Contacts' messages are private — " +
            "never include one contact's private information in a message drafted to another " +
            "unless the user asked for it. Sensitive actions are gated by tool approval; " +
            "do not re-confirm them in text."

    /**
     * Grounds the model in the real wall-clock time. A model's training cutoff otherwise
     * silently stands in for "today" (e.g. it'll search for "richest person 2023" on a
     * device where it's actually 2026) — the current_time tool alone doesn't fix this
     * because nothing forces the model to call it before deciding how to phrase a
     * time-sensitive query.
     */
    fun grounded(system: String, now: ZonedDateTime): String =
        "$system\n\nCurrent date and time: $now. Use this to " +
            "interpret words like \"today\", \"current\", \"latest\", or a bare year correctly " +
            "— your training data has a cutoff well before this date, so never assume it is the present."

    /**
     * Like [grounded], but also inserts the tool inventory rendered live from [tools]
     * (fixes F2: hardcoded tool names in the prompt drifting from the registry). The
     * inventory sits between the persona and the time suffix; an empty registry adds
     * no section at all.
     */
    fun grounded(system: String, now: ZonedDateTime, tools: ToolRegistry): String {
        val inventory = ToolInventory.render(tools)
        val base = if (inventory.isEmpty()) system else "$system\n\n$inventory"
        return grounded(base, now)
    }
}
