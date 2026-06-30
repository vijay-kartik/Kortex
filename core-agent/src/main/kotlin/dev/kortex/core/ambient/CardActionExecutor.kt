package dev.kortex.core.ambient

import dev.kortex.core.tool.RiskLevel

/** Result of trying to perform a card action. */
sealed interface ActionOutcome {
    val message: String
    data class Done(override val message: String) : ActionOutcome
    data class Blocked(override val message: String) : ActionOutcome
    data class Failed(override val message: String) : ActionOutcome
}

/** Performs the actual side effect of a card action (Android intents, etc.). App-provided. */
fun interface CardActionHandler {
    suspend fun handle(card: ActionCard, action: CardAction): ActionOutcome
}

/**
 * The governance gate for executing card actions (pattern 5: Tool Use, 13: HITL, 18:
 * Guardrails) — the executor half of "let tools be used wisely". No action with risk above
 * LOW runs unless it's been [approved], and every attempt is audited regardless of outcome.
 * The actual side effect is delegated to the injected [CardActionHandler].
 */
class CardActionExecutor(
    private val handler: CardActionHandler,
    private val onAudit: (Audit) -> Unit = {},
) {
    enum class Decision { EXECUTED, BLOCKED, FAILED }

    data class Audit(
        val cardId: String,
        val action: String,
        val risk: RiskLevel,
        val decision: Decision,
        val at: Long = System.currentTimeMillis(),
    )

    suspend fun execute(card: ActionCard, action: CardAction, approved: Boolean): ActionOutcome {
        if (action.risk() != RiskLevel.LOW && !approved) {
            audit(card, action, Decision.BLOCKED)
            return ActionOutcome.Blocked("\"${action.label}\" needs confirmation before it runs.")
        }
        val outcome = runCatching { handler.handle(card, action) }
            .getOrElse { ActionOutcome.Failed(it.message ?: "execution failed") }
        audit(card, action, if (outcome is ActionOutcome.Done) Decision.EXECUTED else Decision.FAILED)
        return outcome
    }

    private fun audit(card: ActionCard, action: CardAction, decision: Decision) =
        onAudit(Audit(card.id, action.label, action.risk(), decision))
}
