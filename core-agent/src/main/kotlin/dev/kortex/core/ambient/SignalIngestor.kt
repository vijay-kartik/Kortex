package dev.kortex.core.ambient

import dev.kortex.core.store.ContactDao
import dev.kortex.core.store.ConversationDao
import dev.kortex.core.store.SignalDao
import dev.kortex.core.store.toDomain
import dev.kortex.core.store.toEntity

/** Result of ingesting one raw signal. */
sealed interface IngestResult {
    data class Stored(val signal: Signal, val conversation: Conversation) : IngestResult
    data class Dropped(val reason: String) : IngestResult
}

/**
 * The ingestion gate + writer. For each raw [Signal]:
 *  1. Resolve the sender to a saved contact ([IdentityResolver]).
 *  2. If it's not a saved contact, DROP it (never stored) — per the product rule that only
 *     saved (named) contacts are curated.
 *  3. Otherwise persist the signal (stamped with contactId), bump the contact's recency,
 *     and fold it into that contact's single rolling cross-medium [Conversation].
 */
class SignalIngestor(
    private val resolver: IdentityResolver,
    private val signals: SignalDao,
    private val conversations: ConversationDao,
    private val contacts: ContactDao,
) {
    suspend fun ingest(raw: Signal): IngestResult {
        val resolution = resolver.resolve(raw.senderHandle)
        if (resolution !is Resolution.Matched) {
            return IngestResult.Dropped("sender not a saved contact")
        }

        val signal = raw.copy(contactId = resolution.contactId)
        signals.insert(signal.toEntity())
        contacts.touch(resolution.contactId, signal.timestampMillis)

        val conversation = upsertConversation(resolution.contactId, signal)
        return IngestResult.Stored(signal, conversation)
    }

    private suspend fun upsertConversation(contactId: String, signal: Signal): Conversation {
        val existing = conversations.forContact(contactId)?.toDomain()
        val contactName = contacts.byId(contactId)?.displayName ?: existing?.contactName ?: "Unknown"

        val updated = if (existing == null) {
            Conversation(
                id = "conv_$contactId",
                contactId = contactId,
                contactName = contactName,
                sourceAppIds = setOf(signal.source.appId),
                signalIds = listOf(signal.id),
                firstAtMillis = signal.timestampMillis,
                lastAtMillis = signal.timestampMillis,
            )
        } else {
            existing.copy(
                contactName = contactName,
                sourceAppIds = existing.sourceAppIds + signal.source.appId,
                signalIds = existing.signalIds + signal.id,
                firstAtMillis = minOf(existing.firstAtMillis, signal.timestampMillis),
                lastAtMillis = maxOf(existing.lastAtMillis, signal.timestampMillis),
            )
        }
        conversations.upsert(updated.toEntity())
        return updated
    }
}
