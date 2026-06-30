package dev.kortex.core.ambient

import dev.kortex.core.llm.LlmProvider
import dev.kortex.core.llm.LlmRequest
import dev.kortex.core.llm.Models
import dev.kortex.core.state.Message
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** What to do about a contact's freshly-updated conversation (pattern 2: Routing). */
enum class TriageDecision {
    /** Worth surfacing now — proceed to card generation. */
    GENERATE_CARD,

    /** Useful context but nothing to act on — persist as memory only. */
    STORE_MEMORY,

    /** Trivial/noise (e.g. "ok", "👍", spam) — do nothing. */
    IGNORE,
}

data class TriageResult(val decision: TriageDecision, val rationale: String)

/** Everything triage looks at: the new activity plus the context it already has. */
data class TriageContext(
    val contactName: String,
    val newSignals: List<Signal>,
    /** The conversation's rolling cross-medium summary, if any. */
    val conversationSummary: String? = null,
    /** Relevant snippets already known about this contact (from memory/RAG). */
    val recentMemory: List<String> = emptyList(),
)

/**
 * The triage step of the ambient pipeline (pattern 2: Routing). It classifies an updated
 * conversation into one of three routes so the rest of the pipeline only does expensive
 * work (card generation on the strong model) when it's warranted. Triage itself runs on the
 * FAST model (pattern 16: Resource-Aware Optimization) — most updates don't deserve a card.
 *
 * Bias is intentionally conservative: when unsure it prefers STORE_MEMORY over GENERATE_CARD,
 * so the agent accumulates context rather than spamming the user with low-value cards.
 */
class AmbientTriage(
    private val llm: LlmProvider,
    private val model: String = Models.FAST,
) {
    suspend fun triage(context: TriageContext): TriageResult {
        if (context.newSignals.isEmpty()) {
            return TriageResult(TriageDecision.IGNORE, "no new signals")
        }

        val resp = llm.complete(
            LlmRequest(
                model = model,
                messages = listOf(Message(Message.Role.USER, buildPrompt(context))),
                temperature = 0.0,
            )
        )
        return parse(resp.message.content)
    }

    private fun buildPrompt(ctx: TriageContext): String {
        val activity = ctx.newSignals.joinToString("\n") { s ->
            "- [${formatTime(s.timestampMillis)}] via ${s.source.appLabel}: ${s.content}"
        }
        val memory = ctx.recentMemory.takeIf { it.isNotEmpty() }
            ?.joinToString("\n") { "- $it" }
            ?: "(none)"

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

    private fun parse(content: String): TriageResult {
        val lines = content.trim().lines()
        // Classify from the label line only, so words in the rationale can't flip the route.
        val label = lines.firstOrNull { it.isNotBlank() }?.uppercase().orEmpty()
        val decision = when {
            "GENERATE_CARD" in label || "CARD" in label -> TriageDecision.GENERATE_CARD
            "IGNORE" in label -> TriageDecision.IGNORE
            "STORE_MEMORY" in label || "MEMORY" in label -> TriageDecision.STORE_MEMORY
            else -> TriageDecision.STORE_MEMORY // conservative default
        }
        val rationale = lines.drop(1).joinToString(" ").trim().ifBlank { content.trim() }
        return TriageResult(decision, rationale)
    }

    private fun formatTime(millis: Long): String =
        SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(millis))
}
