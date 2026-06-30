package dev.kortex.app

import dev.kortex.core.ambient.AmbientCoordinator
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
 */
class WaGateway(private val coordinator: AmbientCoordinator) {

    suspend fun onMessages(results: List<MessageDecryptor.Result>) {
        results.forEach { result ->
            val text = extractText(result) ?: return@forEach
            coordinator.onSignal(
                Signal(
                    id = UUID.randomUUID().toString(),
                    source = SignalSource(appId = "net.whatsapp", appLabel = "WhatsApp"),
                    kind = SignalKind.MESSAGE,
                    direction = Direction.INCOMING,
                    senderHandle = Handle(HandleType.PHONE, result.sender.user),
                    content = text,
                    timestampMillis = System.currentTimeMillis(),
                )
            )
        }
    }

    private fun extractText(result: MessageDecryptor.Result): String? {
        val message = result.message
        return message.conversation?.takeIf { it.isNotBlank() }
            ?: message.extendedTextMessage?.text?.takeIf { it.isNotBlank() }
    }
}
