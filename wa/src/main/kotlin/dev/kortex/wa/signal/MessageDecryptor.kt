package dev.kortex.wa.signal

import android.util.Log
import dev.kortex.wa.binary.Jid
import dev.kortex.wa.binary.Node
import org.whispersystems.libsignal.SessionCipher
import org.whispersystems.libsignal.SignalProtocolAddress
import org.whispersystems.libsignal.groups.GroupCipher
import org.whispersystems.libsignal.groups.GroupSessionBuilder
import org.whispersystems.libsignal.groups.SenderKeyName
import org.whispersystems.libsignal.protocol.PreKeySignalMessage
import org.whispersystems.libsignal.protocol.SenderKeyDistributionMessage
import org.whispersystems.libsignal.protocol.SignalMessage
import proto.Message

/**
 * Decrypts the `<enc>` payloads inside a WhatsApp `<message>` node into [proto.Message]s,
 * a port of whatsmeow's `decryptDM` / `decryptGroupMsg` / `unpadMessage`:
 *  - `pkmsg` — Signal PreKey message (session setup, X3DH) via [SessionCipher]
 *  - `msg`   — Signal message (Double Ratchet) via [SessionCipher]
 *  - `skmsg` — group SenderKey message via [GroupCipher] (requires the sender's SKDM first)
 *
 * Also runs whatsmeow's `processProtocolParts` on each decrypted message: installs any
 * `senderKeyDistributionMessage` (without which group `skmsg`s can never decrypt) and unwraps the
 * `deviceSentMessage` / ephemeral / view-once envelopes so callers see the real payload.
 *
 * All calls are blocking (libsignal) — run on a background dispatcher.
 *
 * @param ownUser the phone-number part of our own device JID, used to flag [Result.fromMe].
 */
class MessageDecryptor(
    private val store: WaSignalStore,
    private val ownUser: String? = null,
    private val ownLid: String? = null,
    private val lids: LidDirectory? = null,
) {

    data class Result(
        val id: String,
        val sender: Jid,
        val chat: Jid,
        val message: Message,
        val encType: String,
        val fromMe: Boolean,
        val timestampMillis: Long,
        /**
         * The sender's phone number, when one can be determined. WhatsApp increasingly addresses
         * people by LID (an opaque privacy id) rather than by number, and a LID will never match a
         * saved contact — so the `*_pn` attributes the server ships alongside are the only way to
         * reach the contact book.
         */
        val senderPhone: String?,
        /**
         * The other party's phone number when *we* sent this message from another device — the
         * sender is then our own account, so [senderPhone] says nothing about who the chat is with.
         */
        val recipientPhone: String?,
        /**
         * The stanza's `category`. "peer" marks device-to-device plumbing (app-state sync, key
         * distribution) rather than anything a person sent.
         */
        val category: String?,
    )

    /** One `<enc>` part that could not be decrypted, kept so the caller can decide about retries. */
    data class Failure(val encType: String, val cause: Throwable)

    /**
     * Outcome for one `<message>` node. Parts are independent: a group message carries both a
     * DM-level `enc` and an `skmsg`, and one failing must not discard the other.
     */
    data class Batch(val results: List<Result>, val failures: List<Failure>)

    fun decrypt(messageNode: Node): Batch {
        val from = messageNode.jidAttr("from") ?: error("message has no 'from'")
        val participant = messageNode.jidAttr("participant")
        val sender = participant ?: from   // group sender vs DM peer
        val chat = from
        val id = messageNode.attr("id").orEmpty()
        // `t` is the server-stamped send time in epoch seconds; offline-flushed messages can be
        // hours old, so preserve it rather than stamping arrival time.
        val timestamp = messageNode.attr("t")?.toLongOrNull()?.times(1000) ?: System.currentTimeMillis()
        val senderPhone = senderPhoneOf(messageNode, sender)
        val category = messageNode.attr("category")

        // The SKDM rides in the DM-level enc (`pkmsg`/`msg`) and must be installed before the
        // `skmsg` in the same node is attempted, so decrypt the non-group parts first.
        val encs = messageNode.childrenWithTag("enc")
            .sortedBy { if (it.attr("type") == "skmsg") 1 else 0 }

        val results = mutableListOf<Result>()
        val failures = mutableListOf<Failure>()

        for (enc in encs) {
            val type = enc.attr("type") ?: continue
            val version = enc.attr("v")?.toIntOrNull() ?: 2
            val content = enc.contentBytes() ?: continue
            try {
                val address = sender.signalAddress()
                if (type == "pkmsg" || type == "msg") {
                    // Whether a session already exists for the canonical address is exactly what
                    // separates "this bootstraps a new session" from "this should ride an existing
                    // ratchet" — and a pkmsg arriving with no session is what forces a pre-key
                    // lookup that can fail.
                    Log.i(TAG, "msg id=$id $type session=${address.name}.${address.deviceId} exists=${store.containsSession(address)}")
                }
                val plaintext = when (type) {
                    "pkmsg" -> unpad(
                        SessionCipher(store, address).decrypt(PreKeySignalMessage(content)),
                        version,
                    )
                    "msg" -> unpad(
                        SessionCipher(store, address).decrypt(SignalMessage(content)),
                        version,
                    )
                    "skmsg" -> unpad(
                        GroupCipher(store, SenderKeyName(chat.toString(), address)).decrypt(content),
                        version,
                    )
                    else -> {
                        Log.i(TAG, "msg id=$id ignoring unknown enc type=$type")
                        continue
                    }
                }

                val raw = Message.ADAPTER.decode(plaintext)
                val unwrapped = unwrap(raw)
                processProtocolParts(chat, sender, unwrapped)

                results += Result(
                    id = id,
                    sender = sender,
                    chat = chat,
                    message = unwrapped,
                    encType = type,
                    fromMe = raw.deviceSentMessage != null || isOwn(sender),
                    timestampMillis = timestamp,
                    senderPhone = senderPhone,
                    recipientPhone = recipientPhoneOf(messageNode, raw),
                    category = category,
                )
            } catch (e: Exception) {
                failures += Failure(type, e)
            }
        }
        return Batch(results, failures)
    }

    /**
     * Resolve the sender to a phone number. A phone-addressed JID already is one; a LID-addressed
     * sender is only usable via the `participant_pn` / `sender_pn` attributes the server attaches.
     * Returns null when neither is available — the caller must not pass a LID off as a number.
     */
    private fun senderPhoneOf(node: Node, sender: Jid): String? {
        if (!sender.isLid) return sender.user.takeIf { it.isNotEmpty() }
        val onThisStanza = (node.jidAttr("participant_pn") ?: node.jidAttr("sender_pn"))
            ?.user?.takeIf { it.isNotEmpty() }
        // Fall back to a pairing learned from an earlier stanza — the server does not always
        // repeat the number on the message you need it for.
        return onThisStanza ?: phoneOf(sender)
    }

    /**
     * Who a message we sent was addressed to. The `deviceSentMessage` envelope names the chat
     * directly; failing that the server's `peer_recipient_pn` / `recipient` attributes do.
     */
    private fun recipientPhoneOf(node: Node, raw: Message): String? {
        raw.deviceSentMessage?.destinationJid?.let { dest ->
            phoneOf(Jid.parse(dest))?.let { return it }
        }
        node.jidAttr("peer_recipient_pn")?.user?.takeIf { it.isNotEmpty() }?.let { return it }
        return node.jidAttr("recipient")?.let(::phoneOf)
    }

    /** A phone-addressed JID is already a number; a LID needs the directory. */
    private fun phoneOf(jid: Jid): String? =
        if (jid.isLid) lids?.phoneFor(jid) else jid.user.takeIf { it.isNotEmpty() }

    /** Our own account, addressed either by phone number or by LID. */
    private fun isOwn(sender: Jid): Boolean =
        (ownUser != null && sender.user == ownUser) || (ownLid != null && sender.user == ownLid)

    /**
     * Peel the envelopes WhatsApp wraps real content in (whatsmeow `processProtocolParts`).
     * Without this a disappearing-message or view-once text decrypts fine but reads as empty.
     * Loops because envelopes nest (e.g. deviceSent → ephemeral → the actual text).
     */
    private fun unwrap(message: Message): Message {
        var current = message
        repeat(MAX_UNWRAP_DEPTH) {
            val inner = current.deviceSentMessage?.message
                ?: current.ephemeralMessage?.message
                ?: current.viewOnceMessage?.message
                ?: current.viewOnceMessageV2?.message
                ?: current.viewOnceMessageV2Extension?.message
                ?: current.documentWithCaptionMessage?.message
                ?: current.editedMessage?.message
                ?: return current
            current = inner
        }
        return current
    }

    /**
     * Install a sender key so future `skmsg`s from [sender] in [chat] decrypt. Mirrors whatsmeow:
     * keyed on the chat JID (which is what the `skmsg` path looks up), and only meaningful in a
     * group — an SKDM in a DM is a protocol oddity worth logging rather than acting on.
     */
    private fun processProtocolParts(chat: Jid, sender: Jid, message: Message) {
        val skdm = message.senderKeyDistributionMessage ?: return
        if (chat.server != Jid.GROUP_SERVER) {
            Log.w(TAG, "sender key distribution in non-group chat from ${sender.user}, ignoring")
            return
        }
        val axolotl = skdm.axolotlSenderKeyDistributionMessage?.toByteArray() ?: return
        if (skdm.groupId != null && skdm.groupId != chat.toString()) {
            Log.w(TAG, "SKDM groupId=${skdm.groupId} != chat=$chat; keying on chat")
        }
        runCatching { processSenderKeyDistribution(chat, sender, axolotl) }
            .onSuccess { Log.i(TAG, "installed sender key for ${sender.user} in $chat") }
            .onFailure { Log.w(TAG, "sender key install failed for ${sender.user} in $chat", it) }
    }

    /** Process a sender-key distribution message so future group `skmsg`s from [sender] decrypt. */
    fun processSenderKeyDistribution(chat: Jid, sender: Jid, skdm: ByteArray) {
        GroupSessionBuilder(store).process(
            SenderKeyName(chat.toString(), sender.signalAddress()),
            SenderKeyDistributionMessage(skdm),
        )
    }

    /** WhatsApp pads plaintext (v2): the last byte is the pad length. v3 is unpadded. */
    private fun unpad(plaintext: ByteArray, version: Int): ByteArray {
        if (version == 3) return plaintext
        require(plaintext.isNotEmpty()) { "empty plaintext" }
        val padLength = plaintext.last().toInt() and 0xFF
        require(padLength in 1..plaintext.size) { "invalid padding" }
        return plaintext.copyOfRange(0, plaintext.size - padLength)
    }

    /**
     * The Signal session address for a JID.
     *
     * One physical device can be addressed two ways — by phone number (`…@s.whatsapp.net`, used
     * for app-state/peer stanzas) and by LID (`…@lid`, used for message fan-out) — but it has a
     * single Signal identity, and a sender caches a single pre-key bundle for it. Keying sessions
     * off whichever form happened to arrive splits one device across two session slots: the first
     * stanza establishes a session and consumes the bundle's one-time pre-key, then the same
     * device's other addressing finds no session, tries to bootstrap a fresh one from the same
     * cached bundle, and fails with `InvalidKeyIdException: no pre-key N` — while the rest of the
     * batch sits unused.
     *
     * So the phone number is the canonical name whenever we know it, and a session already stored
     * under the LID form is migrated across rather than abandoned.
     */
    private fun Jid.signalAddress(): SignalProtocolAddress {
        val canonical = SignalProtocolAddress(if (isLid) lids?.phoneFor(this) ?: user else user, device)
        if (isLid && canonical.name != user) {
            val raw = SignalProtocolAddress(user, device)
            if (store.migrateSession(raw, canonical)) {
                Log.i(TAG, "migrated session ${raw.name}.${raw.deviceId} -> ${canonical.name}.${canonical.deviceId}")
            }
        }
        return canonical
    }

    companion object {
        private const val TAG = "KortexWA"
        private const val MAX_UNWRAP_DEPTH = 5

        /**
         * Which payload a decrypted message actually carries — for logs, so "nothing showed up"
         * can be told apart from "it was a sticker".
         */
        fun kindOf(m: Message): String = when {
            m.conversation != null -> "conversation"
            m.extendedTextMessage != null -> "extendedTextMessage"
            m.imageMessage != null -> "imageMessage"
            m.videoMessage != null -> "videoMessage"
            m.audioMessage != null -> "audioMessage"
            m.documentMessage != null -> "documentMessage"
            m.stickerMessage != null -> "stickerMessage"
            m.contactMessage != null -> "contactMessage"
            m.contactsArrayMessage != null -> "contactsArrayMessage"
            m.locationMessage != null -> "locationMessage"
            m.liveLocationMessage != null -> "liveLocationMessage"
            m.reactionMessage != null -> "reactionMessage"
            m.pollCreationMessage != null -> "pollCreationMessage"
            m.pollUpdateMessage != null -> "pollUpdateMessage"
            m.listMessage != null -> "listMessage"
            m.buttonsMessage != null -> "buttonsMessage"
            m.templateMessage != null -> "templateMessage"
            m.groupInviteMessage != null -> "groupInviteMessage"
            m.ptvMessage != null -> "ptvMessage"
            m.eventMessage != null -> "eventMessage"
            m.protocolMessage != null -> "protocolMessage"
            m.senderKeyDistributionMessage != null -> "senderKeyDistributionMessage"
            else -> "unknown"
        }

        /** The plain-text body, or null when the payload carries no text (media, reactions, …). */
        fun textOf(m: Message): String? =
            m.conversation?.takeIf { it.isNotBlank() }
                ?: m.extendedTextMessage?.text?.takeIf { it.isNotBlank() }

        /**
         * Device-to-device plumbing rather than anything a person sent: app-state sync between
         * your own devices (`category=peer`), protocol payloads like revokes and
         * disappearing-message settings, and group sender-key distribution. These are still
         * decrypted — that is how sender keys get installed and sessions advance — but callers
         * that observe or act on "real" messages should skip them. Linking alone produces a burst
         * of them.
         */
        fun isProtocolTraffic(result: Result): Boolean {
            val kind = kindOf(result.message)
            return result.category == "peer" ||
                kind == "protocolMessage" ||
                kind == "senderKeyDistributionMessage"
        }
    }
}
