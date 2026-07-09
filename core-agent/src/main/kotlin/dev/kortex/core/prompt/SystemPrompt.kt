package dev.kortex.core.prompt

import dev.kortex.core.tool.ToolRegistry
import java.time.ZonedDateTime

/**
 * The main agent's system prompt: the default persona/tool-use instructions, the tool
 * inventory rendered live from the registry (see [ToolInventory]), and the time-grounding
 * suffix appended by [grounded] per call.
 */
object SystemPrompt {

    const val DEFAULT =
        "You are Kortex, a capable on-device agent. When you need to take an action " +
            "(send a message, create an event, etc.), use the appropriate tool immediately. " +
            "Do NOT ask the user for permission in text; the system will automatically " +
            "prompt the user for approval when you call a tool. Explain your reasoning briefly. " +
            "Never tell the user to visit a link you can open yourself — open it and answer directly."

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
