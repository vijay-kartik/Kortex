package dev.kortex.wa.signal

import android.util.Log
import dev.kortex.wa.WaLog
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
internal class LidDirectory(private val kv: KeyValueStore) {

    /**
     * Record every (LID, phone) pair this node exposes. WhatsApp names them by position rather
     * than by role, so all three known pairings are harvested regardless of message direction.
     */
    fun record(node: Node) {
        PAIRS.forEach { (lidAttr, pnAttr) ->
            val lid = node.jidAttr(lidAttr)?.takeIf { it.isLid } ?: return@forEach
            val phone = node.jidAttr(pnAttr)?.user?.takeIf { it.isNotEmpty() } ?: return@forEach
            record(lid.user, phone, "$lidAttr/$pnAttr")
        }
    }

    /**
     * Record one pairing directly, for mappings the server never spells out in a `*_pn` attribute.
     * Our own account is the important case: the `success` node gives us our LID, and our device
     * JID gives us our number, but no single stanza ever pairs them.
     */
    fun record(lid: String, phone: String, source: String) {
        if (lid.isEmpty() || phone.isEmpty()) return
        if (kv.get(KeyValueStore.NS_LID_PN, lid)?.toString(Charsets.UTF_8) == phone) return
        kv.put(KeyValueStore.NS_LID_PN, lid, phone.toByteArray(Charsets.UTF_8))
        Log.i(TAG, "lid $lid -> $phone (via $source)")
    }

    /** The phone number for [jid], or null if we've never been told it. */
    fun phoneFor(jid: Jid): String? {
        if (!jid.isLid) return jid.user.takeIf { it.isNotEmpty() }
        return kv.get(KeyValueStore.NS_LID_PN, jid.user)?.toString(Charsets.UTF_8)?.takeIf { it.isNotEmpty() }
    }

    private companion object {
        val TAG: String get() = WaLog.tag

        /** LID-valued attribute paired with the attribute carrying that party's phone number. */
        val PAIRS = listOf(
            "from" to "sender_pn",
            "participant" to "participant_pn",
            "recipient" to "peer_recipient_pn",
        )
    }
}
