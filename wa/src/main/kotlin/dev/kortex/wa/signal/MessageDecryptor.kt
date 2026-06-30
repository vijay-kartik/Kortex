package dev.kortex.wa.signal

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
 * All calls are blocking (libsignal) — run on a background dispatcher.
 */
class MessageDecryptor(private val store: WaSignalStore) {

    data class Result(val sender: Jid, val chat: Jid, val message: Message, val encType: String)

    fun decrypt(messageNode: Node): List<Result> {
        val from = messageNode.jidAttr("from") ?: error("message has no 'from'")
        val participant = messageNode.jidAttr("participant")
        val sender = participant ?: from   // group sender vs DM peer
        val chat = from

        return messageNode.childrenWithTag("enc").mapNotNull { enc ->
            val type = enc.attr("type") ?: return@mapNotNull null
            val version = enc.attr("v")?.toIntOrNull() ?: 2
            val content = enc.contentBytes() ?: return@mapNotNull null

            val plaintext = when (type) {
                "pkmsg" -> unpad(
                    SessionCipher(store, sender.signalAddress()).decrypt(PreKeySignalMessage(content)),
                    version,
                )
                "msg" -> unpad(
                    SessionCipher(store, sender.signalAddress()).decrypt(SignalMessage(content)),
                    version,
                )
                "skmsg" -> unpad(
                    GroupCipher(store, SenderKeyName(chat.toString(), sender.signalAddress())).decrypt(content),
                    version,
                )
                else -> return@mapNotNull null
            }
            Result(sender, chat, Message.ADAPTER.decode(plaintext), type)
        }
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

    private fun Jid.signalAddress() = SignalProtocolAddress(user, device)
}
