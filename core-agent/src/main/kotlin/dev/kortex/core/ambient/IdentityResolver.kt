package dev.kortex.core.ambient

import dev.kortex.core.store.ContactDao
import dev.kortex.core.store.ContactEntity
import dev.kortex.core.store.toDomain

/** Outcome of resolving an incoming handle to a saved contact. */
sealed interface Resolution {
    data class Matched(val contactId: String, val confidence: Double) : Resolution

    /** Sender isn't a saved contact — per the product rule, the signal is dropped. */
    data object OutOfScope : Resolution
}

/**
 * Maps an incoming [Handle] to a saved contact. The saved contact book is the canonical
 * registry: a signal is in scope ONLY if it resolves to a saved contact.
 *
 *  - PHONE: compare the last 10 digits against saved contacts' phone handles (country-code
 *    tolerant), exact match wins.
 *  - EMAIL: case-insensitive match against saved email handles.
 *  - name-only (USERNAME/OTHER, e.g. a WhatsApp notification display name): fuzzy/first-name
 *    match; on collisions, tie-break by recency then affinity (favorite/frequent).
 */
class IdentityResolver(private val contacts: ContactDao) {

    suspend fun resolve(handle: Handle): Resolution {
        val candidates = contacts.all()
        if (candidates.isEmpty()) return Resolution.OutOfScope
        return when (handle.type) {
            HandleType.PHONE -> resolveByPhone(handle.value, candidates)
            HandleType.EMAIL -> resolveByEmail(handle.value, candidates)
            HandleType.USERNAME, HandleType.OTHER -> resolveByName(handle.value, candidates)
        }
    }

    private fun resolveByPhone(value: String, candidates: List<ContactEntity>): Resolution {
        val key = phoneKey(value)
        if (key.isEmpty()) return Resolution.OutOfScope
        val hit = candidates.firstOrNull { entity ->
            entity.toDomain().handles.any { it.type == HandleType.PHONE && phoneKey(it.value) == key }
        }
        return hit?.let { Resolution.Matched(it.id, 0.99) } ?: Resolution.OutOfScope
    }

    private fun resolveByEmail(value: String, candidates: List<ContactEntity>): Resolution {
        val q = value.trim().lowercase()
        val hit = candidates.firstOrNull { entity ->
            entity.toDomain().handles.any { it.type == HandleType.EMAIL && it.value.trim().lowercase() == q }
        }
        return hit?.let { Resolution.Matched(it.id, 0.95) } ?: Resolution.OutOfScope
    }

    private fun resolveByName(value: String, candidates: List<ContactEntity>): Resolution {
        val q = value.trim().lowercase()
        if (q.isEmpty()) return Resolution.OutOfScope
        val qFirst = q.substringBefore(' ')

        val exact = candidates.filter { it.displayName.trim().lowercase() == q }
        val firstName = candidates.filter {
            val n = it.displayName.trim().lowercase()
            n != q && (n.substringBefore(' ') == qFirst || n == qFirst)
        }

        return when {
            exact.size == 1 -> Resolution.Matched(exact.first().id, 0.9)
            exact.size > 1 -> Resolution.Matched(tieBreak(exact).id, 0.6)
            firstName.size == 1 -> Resolution.Matched(firstName.first().id, 0.7)
            firstName.size > 1 -> Resolution.Matched(tieBreak(firstName).id, 0.5)
            else -> Resolution.OutOfScope
        }
    }

    /** Collision tie-break: most recent interaction first, then affinity, then closeness score. */
    private fun tieBreak(candidates: List<ContactEntity>): ContactEntity =
        candidates.maxWith(
            compareBy(
                { it.lastInteractionMillis },
                { affinityRank(it.affinity) },
                { it.score ?: 0.0 },
            )
        )

    private fun affinityRank(affinity: String): Int = when (affinity) {
        ContactAffinity.FAVORITE.name -> 2
        ContactAffinity.FREQUENT.name -> 1
        else -> 0
    }

    private fun phoneKey(raw: String): String {
        val digits = raw.filter { it.isDigit() }
        return if (digits.length >= 10) digits.takeLast(10) else digits
    }
}
