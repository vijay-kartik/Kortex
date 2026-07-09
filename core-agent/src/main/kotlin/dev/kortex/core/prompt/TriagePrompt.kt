package dev.kortex.core.prompt

import dev.kortex.core.ambient.TriageContext

/**
 * Classification prompt for [dev.kortex.core.ambient.AmbientTriage] (pattern 2: Routing).
 * [activity] and [memory] are the pre-formatted "- ..." bullet lists built from
 * [TriageContext.newSignals] (with timestamps) and [TriageContext.recentMemory].
 */
object TriagePrompt {

    fun build(ctx: TriageContext, activity: String, memory: String): String {
        return """
            You triage incoming communications for a personal assistant. Decide what to do
            about new activity from the contact "${ctx.contactName}".

            Choose exactly one:
            - GENERATE_CARD: there is something the user likely wants to see or act on now
              (a question to answer, a request, a plan to confirm, a time-sensitive item).
            - STORE_MEMORY: useful context worth remembering, but nothing to act on now.
            - IGNORE: trivial, noise, or already-handled chit-chat ("ok", reactions, spam).

            When unsure, prefer STORE_MEMORY over GENERATE_CARD.

            Conversation summary so far:
            ${ctx.conversationSummary ?: "(none yet)"}

            What we already know about this contact:
            $memory

            New activity:
            $activity

            Respond with the label on the first line, then a short reason on the next line.
        """.trimIndent()
    }
}
