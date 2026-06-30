package dev.kortex.core.ambient

/**
 * Produces an [AnalysisOutcome] (summary, card(s), extracted entities/mentions/relations,
 * and any memories) for a card-worthy conversation. Implemented later by an LLM-backed node
 * on the strong model (pattern 16: the expensive step, run only when triage says so).
 */
fun interface CardGenerator {
    suspend fun generate(context: TriageContext): AnalysisOutcome
}

/** Distills useful-but-not-actionable activity into memories. Implemented later (LLM-backed). */
fun interface MemoryWriter {
    suspend fun write(context: TriageContext): List<MemoryEntry>
}

/** Outcome of running the ambient pipeline over one updated conversation. */
sealed interface AmbientAnalysisResult {
    val rationale: String

    data class Ignored(override val rationale: String) : AmbientAnalysisResult

    data class Stored(
        val memories: List<MemoryEntry>,
        override val rationale: String,
        val dropped: List<String> = emptyList(),
    ) : AmbientAnalysisResult

    data class Carded(
        val outcome: AnalysisOutcome,
        override val rationale: String,
        val dropped: List<String> = emptyList(),
    ) : AmbientAnalysisResult
}

/**
 * The ambient pipeline orchestrator. For one updated cross-medium conversation it:
 *   1. routes via [AmbientTriage] (pattern 2) — generate card / store memory / ignore;
 *   2. runs the expensive generator only on the card path (pattern 16: Resource-Aware);
 *   3. passes everything through [CardGuardrails] (pattern 18) before it can be persisted;
 *   4. persists the surviving outcome via [OutcomeWriter].
 *
 * It owns the *control flow*; the generation steps are injected, so the LLM specifics (and
 * later, retrieval/reflection) evolve without touching this orchestration.
 */
class AmbientAnalyzer(
    private val triage: AmbientTriage,
    private val cardGenerator: CardGenerator,
    private val memoryWriter: MemoryWriter,
    private val guardrails: CardGuardrails,
    private val writer: OutcomeWriter,
) {
    suspend fun analyze(context: TriageContext): AmbientAnalysisResult {
        val routed = triage.triage(context)
        return when (routed.decision) {
            TriageDecision.IGNORE ->
                AmbientAnalysisResult.Ignored(routed.rationale)

            TriageDecision.STORE_MEMORY -> {
                val raw = AnalysisOutcome(memories = memoryWriter.write(context))
                val checked = guardrails.apply(raw)
                writer.persist(checked.outcome)
                AmbientAnalysisResult.Stored(checked.outcome.memories, routed.rationale, checked.dropped)
            }

            TriageDecision.GENERATE_CARD -> {
                val raw = cardGenerator.generate(context)
                val checked = guardrails.apply(raw)
                writer.persist(checked.outcome)
                AmbientAnalysisResult.Carded(checked.outcome, routed.rationale, checked.dropped)
            }
        }
    }
}
