package dev.kortex.core.prompt

import dev.kortex.core.ambient.TriageContext

/**
 * Prompts for [dev.kortex.core.ambient.LlmCardGenerator]: the card-generation prompt and
 * the reviewer prompt for its Reflection pass (pattern 4).
 */
object CardPrompts {

    fun generate(ctx: TriageContext, feedback: String?): String {
        val activity = ctx.newSignals.joinToString("\n") { "- via ${it.source.appLabel}: ${it.content}" }
        val known = ctx.recentMemory.takeIf { it.isNotEmpty() }?.joinToString("\n") { "- $it" } ?: "(none)"
        val revision = feedback?.let { "\nRevise your previous card using this reviewer feedback:\n$it\n" } ?: ""

        return """
            You build a single actionable "card" for a personal assistant about the contact
            "${ctx.contactName}", summarizing what's been shared across all messaging apps and
            suggesting what the user can do next. Base everything ONLY on the activity and
            known facts below — never invent details.
            $revision
            Conversation summary so far:
            ${ctx.conversationSummary ?: "(none yet)"}

            What we already know:
            $known

            New activity (across mediums):
            $activity

            Return ONLY a JSON object:
            {
              "makeCard": true,
              "title": "<short title>",
              "summary": "<combined, medium-agnostic summary of what was shared>",
              "priority": "LOW|MEDIUM|HIGH|URGENT",
              "actions": [
                { "type": "reply_text|share_location|share_media|set_reminder|schedule_checkin|create_event|call",
                  "label": "<button text>", "text": "<message/draft/title if relevant>",
                  "atMillis": <epoch ms if time-based>, "live": false, "mediaType": "IMAGE|FILE|...",
                  "startMillis": <epoch ms for events> }
              ],
              "entities": [ { "type": "PERSON|PLACE|DATE_TIME|EVENT|COMMITMENT|ORGANIZATION|TOPIC|OTHER",
                              "name": "<canonical>", "surfaceText": "<as written>" } ],
              "memories": [ { "content": "<durable fact>", "kind": "FACT|PREFERENCE|EVENT|COMMITMENT|RELATIONSHIP|OTHER",
                              "salience": 0.5, "tags": ["..."] } ]
            }
            Set "makeCard": false if, on reflection, nothing is truly card-worthy.
        """.trimIndent()
    }

    /**
     * Reviewer prompt for the drafted card. [actions] is the pre-formatted one-line summary
     * of the draft's actions ("type (text)" joined with ", ").
     */
    fun reflect(ctx: TriageContext, title: String, summary: String, actions: String): String {
        val activity = ctx.newSignals.joinToString("\n") { "- ${it.content}" }
        return """
            You are a strict reviewer of a proposed assistant card. Check that it is:
            - grounded ONLY in the activity below (no invented facts),
            - genuinely useful/actionable for the user,
            - appropriate (actions don't overreach or assume consent the user didn't give).

            Reply with exactly "OK" if it's good, otherwise "REVISE: <specific feedback>".

            Activity:
            $activity

            Proposed card:
            title: $title
            summary: $summary
            actions: $actions
        """.trimIndent()
    }
}
