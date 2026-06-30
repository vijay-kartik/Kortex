package dev.kortex.core.ambient

import dev.kortex.core.store.ConversationDao
import dev.kortex.core.store.SignalDao
import dev.kortex.core.store.toDomain

/** Result of coordinating one ingestion/analysis cycle. */
sealed interface CoordinationResult {
    /** Sender wasn't a saved contact, or there was nothing to analyze. */
    data class Dropped(val reason: String) : CoordinationResult
    data class Analyzed(val result: AmbientAnalysisResult) : CoordinationResult
}

/**
 * Top of the ambient pipeline — wires ingestion to analysis and assembles the [TriageContext]
 * (conversation summary + new signals + retrieved memory). Exposes both triggers the product
 * needs:
 *  - [onSignal] — real-time: ingest a freshly-arrived signal, then analyze the update.
 *  - [reviewContact] — periodic: re-evaluate a contact's recent activity on a schedule, so a
 *    topic that wasn't card-worthy earlier can resurface when more context exists.
 *
 * It only orchestrates; resolution, generation, guardrails and persistence live in the
 * injected collaborators.
 */
class AmbientCoordinator(
    private val ingestor: SignalIngestor,
    private val analyzer: AmbientAnalyzer,
    private val retriever: MemoryRetriever,
    private val signals: SignalDao,
    private val conversations: ConversationDao,
    private val recentWindow: Int = 20,
) {
    suspend fun onSignal(raw: Signal): CoordinationResult =
        when (val ingest = ingestor.ingest(raw)) {
            is IngestResult.Dropped -> CoordinationResult.Dropped(ingest.reason)
            is IngestResult.Stored -> {
                val context = buildContext(ingest.conversation, listOf(ingest.signal))
                CoordinationResult.Analyzed(analyzer.analyze(context))
            }
        }

    suspend fun reviewContact(contactId: String): CoordinationResult {
        val conversation = conversations.forContact(contactId)?.toDomain()
            ?: return CoordinationResult.Dropped("no conversation for contact")
        val recent = signals.forContact(contactId).takeLast(recentWindow).map { it.toDomain() }
        if (recent.isEmpty()) return CoordinationResult.Dropped("no signals for contact")

        val context = buildContext(conversation, recent)
        return CoordinationResult.Analyzed(analyzer.analyze(context))
    }

    private suspend fun buildContext(conversation: Conversation, newSignals: List<Signal>): TriageContext =
        TriageContext(
            contactName = conversation.contactName,
            newSignals = newSignals,
            conversationSummary = conversation.summary,
            recentMemory = retriever.forContact(conversation.contactId),
        )
}
