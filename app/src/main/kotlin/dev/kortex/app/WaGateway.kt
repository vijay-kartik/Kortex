package dev.kortex.app

import android.util.Log
import dev.kortex.core.ambient.AmbientAnalysisResult
import dev.kortex.core.ambient.AmbientCoordinator
import dev.kortex.core.ambient.CoordinationResult
import dev.kortex.core.ambient.Direction
import dev.kortex.core.ambient.Handle
import dev.kortex.core.ambient.HandleType
import dev.kortex.core.ambient.Signal
import dev.kortex.core.ambient.SignalKind
import dev.kortex.core.ambient.SignalSource
import dev.kortex.wa.signal.MessageDecryptor
import java.util.UUID

/**
 * Bridges decrypted WhatsApp messages into the Kortex ambient pipeline. Each text message
 * becomes a [Signal] (source = WhatsApp, sender handle = the phone number from the JID) and is
 * fed to [AmbientCoordinator.onSignal]; the identity gate then drops anything that isn't a
 * saved contact, and WhatsApp + SMS from the same person merge into one cross-medium thread.
 *
 * Every incoming message is also reported to [onReceived] with what the pipeline decided, so the
 * WhatsApp screen can show what actually arrived. The drops look nothing alike but are easy to
 * confuse: "no text" means the protocol worked and the payload was media; "not a saved contact"
 * means the protocol worked and the *identity gate* rejected the sender.
 */
class WaGateway(
    private val coordinator: AmbientCoordinator,
    private val onReceived: (Received) -> Unit = {},
) {

    /** One message seen on this connection and what became of it. */
    data class Received(
        val id: String,
        /** The other party in the chat: who sent it, or who we sent it to. */
        val phone: String?,
        /** Message body, or null when the payload carries no text (media, reactions, …). */
        val text: String?,
        /** Which `proto.Message` field carried the payload, e.g. `conversation`, `imageMessage`. */
        val kind: String,
        val timestampMillis: Long,
        /** True for messages we sent from another device, mirrored here. */
        val fromMe: Boolean,
        /** What the pipeline decided — null for our own messages, which never enter it. */
        val outcome: Outcome?,
    )

    sealed interface Outcome {
        /** Analyzed and turned into an action card. */
        data object Carded : Outcome

        /** Analyzed and folded into long-term memory, but not card-worthy. */
        data object Remembered : Outcome

        /** Analyzed and deliberately not acted on. */
        data class Ignored(val rationale: String) : Outcome

        /** Never reached analysis. */
        data class Dropped(val reason: String) : Outcome
    }

    suspend fun onMessages(results: List<MessageDecryptor.Result>) {
        results.forEach { result ->
            val kind = MessageDecryptor.kindOf(result.message)
            val text = extractText(result)

            Log.i(
                TAG,
                "gw id=${result.id} sender=${result.sender} phone=${result.senderPhone} " +
                    "chat=${result.chat} kind=$kind fromMe=${result.fromMe} textLen=${text?.length ?: 0}",
            )

            // Our own outgoing message, mirrored to this companion: shown for visibility, but it
            // never enters the pipeline — the ambient layer curates what other people send us.
            val outcome = if (result.fromMe) {
                Log.i(TAG, "gw id=${result.id} own message, not ingested")
                null
            } else {
                ingest(result, kind, text).also { Log.i(TAG, "gw id=${result.id} pipeline: ${it.describe()}") }
            }

            onReceived(
                Received(
                    id = result.id,
                    phone = if (result.fromMe) result.recipientPhone else result.senderPhone,
                    text = text,
                    kind = kind,
                    timestampMillis = result.timestampMillis,
                    fromMe = result.fromMe,
                    outcome = outcome,
                )
            )
        }
    }

    private suspend fun ingest(result: MessageDecryptor.Result, kind: String, text: String?): Outcome {
        if (text == null) return Outcome.Dropped("$kind carries no text")
        // A LID is not a phone number: handing one to the identity gate guarantees a miss and
        // would quietly look identical to "this person isn't in your contacts".
        val phone = result.senderPhone ?: return Outcome.Dropped("sender ${result.sender} has no phone number")

        val coordination = coordinator.onSignal(
            Signal(
                id = UUID.randomUUID().toString(),
                source = SignalSource(appId = "net.whatsapp", appLabel = "WhatsApp"),
                kind = SignalKind.MESSAGE,
                direction = Direction.INCOMING,
                senderHandle = Handle(HandleType.PHONE, phone),
                content = text,
                timestampMillis = result.timestampMillis,
            )
        )
        return when (coordination) {
            is CoordinationResult.Dropped -> Outcome.Dropped(coordination.reason)
            is CoordinationResult.Analyzed -> when (val analysis = coordination.result) {
                is AmbientAnalysisResult.Carded -> Outcome.Carded
                is AmbientAnalysisResult.Stored -> Outcome.Remembered
                is AmbientAnalysisResult.Ignored -> Outcome.Ignored(analysis.rationale)
            }
        }
    }

    private fun Outcome.describe(): String = when (this) {
        is Outcome.Carded -> "Analyzed → card"
        is Outcome.Remembered -> "Analyzed → memory"
        is Outcome.Ignored -> "Analyzed → ignored ($rationale)"
        is Outcome.Dropped -> "Dropped($reason)"
    }

    private fun extractText(result: MessageDecryptor.Result): String? {
        val message = result.message
        return message.conversation?.takeIf { it.isNotBlank() }
            ?: message.extendedTextMessage?.text?.takeIf { it.isNotBlank() }
    }

    private companion object {
        const val TAG = "KortexWA"
    }
}
