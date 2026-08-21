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
import dev.kortex.wa.session.WhatsAppManager
import dev.kortex.wa.signal.MessageDecryptor
import java.util.UUID

/**
 * Bridges decrypted WhatsApp messages (observed by [WhatsAppManager], the reusable `:wa` session)
 * into this app's Kortex ambient pipeline. Each text message becomes a [Signal] (source =
 * WhatsApp, sender handle = the phone number from the JID) and is fed to
 * [AmbientCoordinator.onSignal]; the identity gate then drops anything that isn't a saved contact,
 * and WhatsApp + SMS from the same person merge into one cross-medium thread.
 *
 * What the pipeline decided is reported back via [annotate] so the WhatsApp tab can show it next
 * to the message it belongs to — this is the one piece of Kortex-specific meaning that the `:wa`
 * module itself knows nothing about.
 */
class WaGateway(
    private val coordinator: AmbientCoordinator,
    private val annotate: (id: String, annotation: WhatsAppManager.Annotation) -> Unit = { _, _ -> },
) {

    suspend fun onMessages(results: List<MessageDecryptor.Result>) {
        results.forEach { result ->
            if (result.fromMe || MessageDecryptor.isProtocolTraffic(result)) return@forEach

            val kind = MessageDecryptor.kindOf(result.message)
            val text = MessageDecryptor.textOf(result.message)
            Log.i(
                TAG,
                "gw id=${result.id} sender=${result.sender} phone=${result.senderPhone} " +
                    "chat=${result.chat} kind=$kind textLen=${text?.length ?: 0}",
            )

            val outcome = ingest(result, kind, text)
            Log.i(TAG, "gw id=${result.id} pipeline: ${outcome.describe()}")
            annotate(result.id, WhatsAppManager.Annotation(outcome.label(), isError = outcome is Outcome.Dropped))
        }
    }

    private sealed interface Outcome {
        data object Carded : Outcome
        data object Remembered : Outcome
        data class Ignored(val rationale: String) : Outcome
        data class Dropped(val reason: String) : Outcome
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

    private fun Outcome.label(): String = when (this) {
        is Outcome.Carded -> "Analyzed — card created"
        is Outcome.Remembered -> "Analyzed — saved to memory"
        is Outcome.Ignored -> "Analyzed — no action needed"
        is Outcome.Dropped -> "Dropped: $reason"
    }

    private companion object {
        const val TAG = "KortexWA"
    }
}
