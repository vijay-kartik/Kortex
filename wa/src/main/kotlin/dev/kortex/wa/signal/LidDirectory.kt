package dev.kortex.wa.signal

import android.util.Log
import dev.kortex.wa.binary.Jid
import dev.kortex.wa.binary.Node
import dev.kortex.wa.store.KeyValueStore

/**
 * Remembers which phone number belongs to which LID.
 *
 * WhatsApp addresses stanzas by LID — an opaque privacy id that is never a phone number and so can
 * never match a saved contact. The server does hand out the mapping, but inconsistently: it rides
 * along as a `*_pn` attribute on whichever stanza happens to mention that party, which may not be
 * the message we actually need to resolve. So every pair we ever see is recorded, and later
 * lookups fall back to what was learned earlier.
 *
 * Backed by [KeyValueStore], so it is wiped on logout along with the rest of the account state.
 */
class LidDirectory(private val kv: KeyValueStore) {

    /**
     * Record every (LID, phone) pair this node exposes. WhatsApp names them by position rather
     * than by role, so all three known pairings are harvested regardless of message direction.
     */
    fun record(node: Node) {
        PAIRS.forEach { (lidAttr, pnAttr) ->
            val lid = node.jidAttr(lidAttr)?.takeIf { it.isLid } ?: return@forEach
            val phone = node.jidAttr(pnAttr)?.user?.takeIf { it.isNotEmpty() } ?: return@forEach
            if (lid.user.isEmpty()) return@forEach
            val existing = phoneFor(lid)
            if (existing == phone) return@forEach
            kv.put(KeyValueStore.NS_LID_PN, lid.user, phone.toByteArray(Charsets.UTF_8))
            Log.i(TAG, "lid ${lid.user} -> $phone (via $lidAttr/$pnAttr)")
        }
    }

    /** The phone number for [jid], or null if we've never been told it. */
    fun phoneFor(jid: Jid): String? {
        if (!jid.isLid) return jid.user.takeIf { it.isNotEmpty() }
        return kv.get(KeyValueStore.NS_LID_PN, jid.user)?.toString(Charsets.UTF_8)?.takeIf { it.isNotEmpty() }
    }

    private companion object {
        const val TAG = "KortexWA"

        /** LID-valued attribute paired with the attribute carrying that party's phone number. */
        val PAIRS = listOf(
            "from" to "sender_pn",
            "participant" to "participant_pn",
            "recipient" to "peer_recipient_pn",
        )
    }
}
