package dev.kortex.core.ambient

import dev.kortex.core.tool.RiskLevel

/**
 * Risk policy for card actions (pattern 13: Human-in-the-Loop, pattern 18: Guardrails).
 * The agent may *propose* any action, but acting on the user's behalf — sending a message,
 * sharing location, placing a call — must never happen silently. This classifies each
 * action so the UI/executor can require an explicit confirm (or a preview-and-edit) before
 * the side effect, reusing the same [RiskLevel] vocabulary as the tool governor.
 */
fun CardAction.risk(): RiskLevel = when (this) {
    is CardAction.ReplyText -> RiskLevel.HIGH        // sends a message as the user
    is CardAction.ShareLocation -> RiskLevel.HIGH    // discloses sensitive location
    is CardAction.ShareMedia -> RiskLevel.HIGH       // sends files/photos
    is CardAction.CallContact -> RiskLevel.HIGH      // initiates a call
    is CardAction.ScheduleCheckIn -> RiskLevel.MEDIUM // future outward send (reversible until it fires)
    is CardAction.CreateCalendarEvent -> RiskLevel.MEDIUM
    is CardAction.SetReminder -> RiskLevel.LOW       // local only, no outward effect
    is CardAction.Custom -> RiskLevel.MEDIUM         // unknown → be cautious
}

/** The highest risk among a card's actions (LOW if it has none). */
fun ActionCard.maxRisk(): RiskLevel = actions.maxOfOrNull { it.risk() } ?: RiskLevel.LOW

/** True if any action needs an explicit confirm before execution (everything but local-only). */
fun ActionCard.requiresApproval(): Boolean = actions.any { it.risk() != RiskLevel.LOW }
