package dev.kortex.core.ambient

/**
 * Pattern 18 (Guardrails / Safety) for the ambient pipeline. An LLM analysis step is
 * fallible — it can emit blank, duplicated, contactless, or over-stuffed cards, or memories
 * with junk salience. Nothing the agent generates should reach the user (or the store)
 * without passing through here first. Pure, deterministic, and side-effect free so it's the
 * trustworthy last line before surfacing.
 *
 * [GuardrailReport.dropped] doubles as an observability trail (pattern 19): every rejection
 * is recorded with a reason rather than silently swallowed.
 */
class CardGuardrails(
    private val maxActionsPerCard: Int = 4,
    private val maxCardsPerOutcome: Int = 10,
    private val requireProvenance: Boolean = true,
) {
    data class GuardrailReport(val outcome: AnalysisOutcome, val dropped: List<String>)

    fun apply(outcome: AnalysisOutcome): GuardrailReport {
        val dropped = mutableListOf<String>()
        val seen = mutableSetOf<String>()

        // 1. Validate/sanitize each card (records its own drop reasons).
        val valid = outcome.cards.mapNotNull { sanitizeCard(it, seen, dropped) }

        // 2. Highest priority first, then enforce the per-outcome cap.
        val ranked = valid.sortedByDescending { it.priority.ordinal }
        val cards = ranked.take(maxCardsPerOutcome)
        ranked.drop(maxCardsPerOutcome).forEach {
            dropped += "card ${it.id}: trimmed by per-outcome cap ($maxCardsPerOutcome)"
        }

        // 3. Sanitize memories (drop blanks, clamp salience to 0..1).
        val memories = outcome.memories.mapNotNull { m ->
            if (m.content.isBlank()) {
                dropped += "memory ${m.id}: blank content"
                null
            } else {
                m.copy(salience = m.salience.coerceIn(0.0, 1.0))
            }
        }

        return GuardrailReport(
            outcome = outcome.copy(cards = cards, memories = memories),
            dropped = dropped,
        )
    }

    private fun sanitizeCard(
        card: ActionCard,
        seen: MutableSet<String>,
        dropped: MutableList<String>,
    ): ActionCard? {
        if (card.title.isBlank() || card.summary.isBlank()) {
            dropped += "card ${card.id}: blank title/summary"
            return null
        }
        if (card.contactId.isBlank()) {
            dropped += "card ${card.id}: no contact"
            return null
        }
        if (requireProvenance && card.sourceSignalIds.isEmpty()) {
            dropped += "card ${card.id}: no source signals (unsupported claim)"
            return null
        }
        val key = "${card.contactId}|${card.title.trim().lowercase()}"
        if (!seen.add(key)) {
            dropped += "card ${card.id}: duplicate of an existing card"
            return null
        }

        // Drop repeated actions and cap how many a single card may carry.
        val actions = card.actions.distinctBy { it::class to it.label }.take(maxActionsPerCard)
        return card.copy(actions = actions)
    }
}
