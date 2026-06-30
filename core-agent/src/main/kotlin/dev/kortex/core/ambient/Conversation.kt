package dev.kortex.core.ambient

import kotlinx.serialization.Serializable

/**
 * The cross-medium unit. This is the answer to "a topic starts on SMS and continues on
 * WhatsApp" — a [Conversation] is keyed by the *person* ([contactId]), not the app, and
 * aggregates signals from **every** source the user shares with that contact. The user
 * sees one combined view + [summary] instead of fragments scattered across apps.
 *
 * Identity resolution (see [ContactRef.handles]) is what funnels signals from different
 * apps into the same conversation. [topics] optionally sub-groups long-running threads.
 */
@Serializable
data class Conversation(
    val id: String,
    val contactId: String,
    val contactName: String,
    /** Every app/medium that has contributed to this conversation, e.g. {SMS, WhatsApp}. */
    val sourceAppIds: Set<String> = emptySet(),
    /** Signals that belong to this conversation, across all mediums, in time order. */
    val signalIds: List<String> = emptyList(),
    /** Optional topic labels the agent has detected within the thread. */
    val topics: List<String> = emptyList(),
    /**
     * The agent-maintained, medium-agnostic summary of what has been shared collectively
     * across all apps — the thing actually surfaced to the user.
     */
    val summary: String? = null,
    val firstAtMillis: Long,
    val lastAtMillis: Long,
    val status: ConversationStatus = ConversationStatus.ACTIVE,
)

@Serializable
enum class ConversationStatus { ACTIVE, SNOOZED, ARCHIVED }
