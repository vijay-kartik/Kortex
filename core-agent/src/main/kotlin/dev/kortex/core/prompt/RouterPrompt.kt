package dev.kortex.core.prompt

/** Classification prompt for [dev.kortex.core.pattern.RouterNode] (pattern 2: Routing). */
object RouterPrompt {

    fun build(routes: List<String>, query: String): String {
        return """
            Classify the user request into exactly one of: ${routes.joinToString(", ")}.
            - simple_qa: answerable directly with general knowledge, no tools, no multi-step work.
            - tool_task: needs one or a few tool calls (e.g. sending messages, checking time, calculations).
            - plan: open-ended/multi-step; needs decomposition first.
            Respond with ONLY the label.

            Request: $query
        """.trimIndent()
    }
}
