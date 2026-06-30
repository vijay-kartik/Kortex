package dev.kortex.core.ambient

import dev.kortex.core.llm.LlmProvider
import dev.kortex.core.llm.LlmRequest
import dev.kortex.core.llm.Models
import dev.kortex.core.state.Message
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * LLM-backed [MemoryWriter] for the STORE_MEMORY route (pattern 8: Memory Management).
 * Distills useful-but-not-actionable activity into a few discrete, durable [MemoryEntry]s
 * for future context. Runs on the FAST model (pattern 16) — this path is the cheap one by
 * construction, since triage chose it precisely because nothing needs acting on.
 *
 * The prompt is shown what we already know so it doesn't re-store duplicates, and is told to
 * return an empty list when there's nothing new worth keeping. Embeddings are left null here;
 * vector indexing (RAG) is a separate pass over stored memories.
 */
class LlmMemoryWriter(
    private val llm: LlmProvider,
    private val model: String = Models.FAST,
    private val clock: () -> Long = { System.currentTimeMillis() },
) : MemoryWriter {

    override suspend fun write(context: TriageContext): List<MemoryEntry> {
        if (context.newSignals.isEmpty()) return emptyList()

        val resp = llm.complete(
            LlmRequest(
                model = model,
                messages = listOf(Message(Message.Role.USER, buildPrompt(context))),
                temperature = 0.2,
            )
        )

        val contactId = context.newSignals.firstNotNullOfOrNull { it.contactId }
        val sourceIds = context.newSignals.map { it.id }
        val now = clock()

        return parseDrafts(resp.message.content)
            .filter { it.content.isNotBlank() }
            .map { draft ->
                MemoryEntry(
                    id = UUID.randomUUID().toString(),
                    content = draft.content.trim(),
                    kind = parseKind(draft.kind),
                    contactId = contactId,
                    sourceSignalIds = sourceIds,
                    salience = draft.salience.coerceIn(0.0, 1.0),
                    tags = draft.tags,
                    createdAtMillis = now,
                )
            }
    }

    private fun buildPrompt(ctx: TriageContext): String {
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

    private fun parseDrafts(content: String): List<MemoryDraft> {
        val text = content.trim().removeCodeFences()
        val start = text.indexOf('[')
        val end = text.lastIndexOf(']')
        if (start == -1 || end == -1 || end < start) return emptyList()
        return runCatching {
            json.decodeFromString<List<MemoryDraft>>(text.substring(start, end + 1))
        }.getOrDefault(emptyList())
    }

    private fun parseKind(raw: String): MemoryKind =
        runCatching { MemoryKind.valueOf(raw.trim().uppercase()) }.getOrDefault(MemoryKind.OTHER)

    private fun String.removeCodeFences(): String =
        replace(Regex("```(?:json)?", RegexOption.IGNORE_CASE), "").trim()

    @Serializable
    private data class MemoryDraft(
        val content: String,
        val kind: String = "FACT",
        val salience: Double = 0.5,
        val tags: List<String> = emptyList(),
    )

    private companion object {
        val json = Json { ignoreUnknownKeys = true; isLenient = true }
    }
}
