package dev.kortex.core.prompt

import dev.kortex.core.ambient.MemoryKind
import dev.kortex.core.ambient.TriageContext

/**
 * Extraction prompt for [dev.kortex.core.ambient.LlmMemoryWriter] (pattern 8: Memory
 * Management). Shown what we already know so it doesn't re-store duplicates, and told to
 * return an empty list when there's nothing new worth keeping.
 */
object MemoryPrompt {

    fun build(ctx: TriageContext): String {
        val activity = ctx.newSignals.joinToString("\n") { "- via ${it.source.appLabel}: ${it.content}" }
        val known = ctx.recentMemory.takeIf { it.isNotEmpty() }
            ?.joinToString("\n") { "- $it" } ?: "(none)"
        val kinds = MemoryKind.entries.joinToString(", ") { it.name }

        return """
            Extract durable facts worth remembering about the contact "${ctx.contactName}"
            from the new activity below — things useful for future context (preferences,
            commitments, life events, relationships, stable facts). Do NOT include trivia,
            one-off chit-chat, or anything already in "What we already know".

            Return a JSON array (and nothing else). Each item:
              { "content": "<concise fact>", "kind": "<one of: $kinds>",
                "salience": <0.0-1.0 importance>, "tags": ["..."] }
            Return [] if there is nothing new worth keeping.

            Conversation summary so far:
            ${ctx.conversationSummary ?: "(none yet)"}

            What we already know:
            $known

            New activity:
            $activity
        """.trimIndent()
    }
}
