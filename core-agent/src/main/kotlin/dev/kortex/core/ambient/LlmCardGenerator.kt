package dev.kortex.core.ambient

import dev.kortex.core.llm.LlmProvider
import dev.kortex.core.llm.LlmRequest
import dev.kortex.core.llm.Models
import dev.kortex.core.state.Message
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * LLM-backed [CardGenerator] for the GENERATE_CARD route. Runs on the strong model
 * (pattern 16: the expensive step, reached only when triage decided it's worth it) and
 * produces the full [AnalysisOutcome]: a combined cross-medium summary card with suggested
 * actions, extracted entities/mentions/relations for the graph, and any memories.
 *
 * A **Reflection** pass (pattern 4) then critiques the draft — is it grounded in the actual
 * signals (no invented facts), genuinely useful, and are the actions appropriate/not
 * overreaching? — looping up to [maxReflections] with the critique fed back in. This is the
 * generator's own quality gate; [CardGuardrails] is still the deterministic last line after.
 *
 * The model emits simple JSON drafts; this class maps them to the sealed [CardAction] and
 * graph types in code, which is far more robust than asking the model to match kotlinx's
 * polymorphic discriminator format.
 */
class LlmCardGenerator(
    private val llm: LlmProvider,
    private val generatorModel: String = Models.REASONING,
    private val criticModel: String = Models.REASONING,
    private val maxReflections: Int = 1,
    private val clock: () -> Long = { System.currentTimeMillis() },
) : CardGenerator {

    override suspend fun generate(context: TriageContext): AnalysisOutcome {
        var draft = generateDraft(context, feedback = null) ?: return AnalysisOutcome()

        repeat(maxReflections) {
            val verdict = reflect(context, draft)
            if (verdict.ok) return build(context, draft)
            draft = generateDraft(context, verdict.feedback) ?: return build(context, draft)
        }
        return build(context, draft)
    }

    // --- generation ---

    private suspend fun generateDraft(context: TriageContext, feedback: String?): CardDraft? {
        val resp = llm.complete(
            LlmRequest(
                model = generatorModel,
                messages = listOf(Message(Message.Role.USER, generatePrompt(context, feedback))),
                temperature = 0.4,
            )
        )
        return parseObject(resp.message.content)
    }

    private fun generatePrompt(ctx: TriageContext, feedback: String?): String {
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

    // --- reflection (pattern 4) ---

    private data class Verdict(val ok: Boolean, val feedback: String)

    private suspend fun reflect(context: TriageContext, draft: CardDraft): Verdict {
        val activity = context.newSignals.joinToString("\n") { "- ${it.content}" }
        val prompt = """
            You are a strict reviewer of a proposed assistant card. Check that it is:
            - grounded ONLY in the activity below (no invented facts),
            - genuinely useful/actionable for the user,
            - appropriate (actions don't overreach or assume consent the user didn't give).

            Reply with exactly "OK" if it's good, otherwise "REVISE: <specific feedback>".

            Activity:
            $activity

            Proposed card:
            title: ${draft.title}
            summary: ${draft.summary}
            actions: ${draft.actions.joinToString { it.type + (it.text?.let { t -> " ($t)" } ?: "") }}
        """.trimIndent()

        val resp = llm.complete(
            LlmRequest(model = criticModel, messages = listOf(Message(Message.Role.USER, prompt)), temperature = 0.0)
        )
        val label = resp.message.content.trim().lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
        val needsRevision = label.contains("REVISE", ignoreCase = true) && !label.equals("OK", ignoreCase = true)
        return Verdict(
            ok = !needsRevision,
            feedback = resp.message.content.substringAfter("REVISE:", "").trim(),
        )
    }

    // --- draft -> domain ---

    private fun build(context: TriageContext, draft: CardDraft): AnalysisOutcome {
        val contactId = context.newSignals.firstNotNullOfOrNull { it.contactId }
        val sourceIds = context.newSignals.map { it.id }
        val conversationId = contactId?.let { "conv_$it" }
        val now = clock()

        // Entities + their mentions/relations into the graph.
        val entities = mutableListOf<Entity>()
        val mentions = mutableListOf<Mention>()
        val relations = mutableListOf<Relation>()
        draft.entities.filter { it.name.isNotBlank() }.forEach { d ->
            val entity = Entity(
                id = UUID.randomUUID().toString(),
                type = enumOr(d.type, EntityType.OTHER),
                name = d.name.trim(),
                createdAtMillis = now,
            )
            entities += entity
            val signalId = signalFor(context, d.surfaceText ?: d.name)
            mentions += Mention(
                id = UUID.randomUUID().toString(),
                entityId = entity.id,
                signalId = signalId,
                conversationId = conversationId,
                surfaceText = d.surfaceText ?: d.name,
                confidence = 0.8,
                createdAtMillis = now,
            )
            if (conversationId != null) {
                relations += Relation(
                    id = UUID.randomUUID().toString(),
                    type = RelationType.MENTIONS,
                    from = NodeRef(NodeType.CONVERSATION, conversationId),
                    to = NodeRef(NodeType.ENTITY, entity.id),
                    createdAtMillis = now,
                )
            }
        }

        val memories = draft.memories.filter { it.content.isNotBlank() }.map { m ->
            MemoryEntry(
                id = UUID.randomUUID().toString(),
                content = m.content.trim(),
                kind = enumOr(m.kind, MemoryKind.OTHER),
                contactId = contactId,
                sourceSignalIds = sourceIds,
                salience = m.salience.coerceIn(0.0, 1.0),
                tags = m.tags,
                createdAtMillis = now,
            )
        }

        val cards = if (draft.makeCard && draft.title.isNotBlank() && draft.summary.isNotBlank() && contactId != null) {
            listOf(
                ActionCard(
                    id = UUID.randomUUID().toString(),
                    title = draft.title.trim(),
                    summary = draft.summary.trim(),
                    contactId = contactId,
                    conversationId = conversationId,
                    actions = draft.actions.mapNotNull { it.toCardAction() },
                    priority = enumOr(draft.priority, Priority.MEDIUM),
                    sourceSignalIds = sourceIds,
                    createdAtMillis = now,
                )
            )
        } else {
            emptyList()
        }

        return AnalysisOutcome(cards = cards, memories = memories, entities = entities, mentions = mentions, relations = relations)
    }

    /** Pick the signal a mention most likely came from (content contains the text), else the latest. */
    private fun signalFor(context: TriageContext, text: String): String {
        val needle = text.trim().lowercase()
        return context.newSignals.firstOrNull { it.content.lowercase().contains(needle) }?.id
            ?: context.newSignals.maxByOrNull { it.timestampMillis }?.id
            ?: ""
    }

    private fun ActionDraft.toCardAction(): CardAction? = when (type.trim().lowercase()) {
        "reply_text", "reply" -> CardAction.ReplyText(label ?: "Reply", suggestedText = text.orEmpty())
        "share_location" -> CardAction.ShareLocation(label ?: "Share location", live = live)
        "share_media" -> CardAction.ShareMedia(label ?: "Share", mediaType = enumOr(mediaType, AttachmentType.OTHER))
        "set_reminder" -> CardAction.SetReminder(label ?: "Remind me", text = text.orEmpty(), atMillis = atMillis ?: 0L)
        "schedule_checkin" -> CardAction.ScheduleCheckIn(label ?: "Schedule check-in", message = text.orEmpty(), atMillis = atMillis ?: 0L)
        "create_event" -> CardAction.CreateCalendarEvent(label ?: "Add to calendar", title = title ?: text.orEmpty(), startMillis = startMillis ?: 0L)
        "call" -> CardAction.CallContact(label ?: "Call")
        "" -> null
        else -> CardAction.Custom(label = label ?: type, actionId = type)
    }

    private inline fun <reified T : Enum<T>> enumOr(raw: String?, default: T): T =
        runCatching { enumValueOf<T>(raw!!.trim().uppercase()) }.getOrDefault(default)

    // --- robust JSON object parsing ---

    private fun parseObject(content: String): CardDraft? {
        val text = content.trim().replace(Regex("```(?:json)?", RegexOption.IGNORE_CASE), "").trim()
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start == -1 || end == -1 || end < start) return null
        return runCatching { json.decodeFromString<CardDraft>(text.substring(start, end + 1)) }.getOrNull()
    }

    @Serializable
    private data class CardDraft(
        val makeCard: Boolean = true,
        val title: String = "",
        val summary: String = "",
        val priority: String = "MEDIUM",
        val actions: List<ActionDraft> = emptyList(),
        val entities: List<EntityDraft> = emptyList(),
        val memories: List<MemoryDraft> = emptyList(),
    )

    @Serializable
    private data class ActionDraft(
        val type: String,
        val label: String? = null,
        val text: String? = null,
        val atMillis: Long? = null,
        val live: Boolean = false,
        val mediaType: String? = null,
        val title: String? = null,
        val startMillis: Long? = null,
    )

    @Serializable
    private data class EntityDraft(val type: String, val name: String, val surfaceText: String? = null)

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
