package dev.kortex.core.prompt

/**
 * Critique prompt for [dev.kortex.core.pattern.ReflectNode] (pattern 4: Reflection).
 * [toolsUsed] is the pre-formatted "- name(args)" list of tool calls the assistant already
 * made during this run; blank means none were made.
 */
object ReflectPrompt {

    fun build(toolsUsed: String, request: String, answer: String): String {
        return """
            You are a strict reviewer. Decide whether the assistant's answer fully and
            correctly addresses the user's request.
            The assistant has real, live tools (web search, opening URLs, the device clock);
            facts in its answer may come from those tool results, which are current and
            trustworthy even when they postdate your training data. Never reject an answer
            because its dates are later than what you know, and never claim the assistant
            cannot search the web or access real-time information — it can.
            - If the answer is good, reply with exactly: OK
            - Otherwise reply: REVISE: <specific, actionable feedback>

            Tool calls the assistant already made during this run:
            ${toolsUsed.ifBlank { "(none)" }}

            User request:
            $request

            Assistant answer:
            $answer
        """.trimIndent()
    }
}
