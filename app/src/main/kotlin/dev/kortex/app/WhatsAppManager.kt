package dev.kortex.app

import android.content.Context
import android.content.Intent
import androidx.room.Room
import dev.kortex.core.ambient.AmbientCoordinator
import dev.kortex.wa.client.WAClient
import dev.kortex.wa.signal.MessageDecryptor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * App-level owner of the native WhatsApp connection: builds the Room-backed stores, runs the
 * [WAClient] on a background scope (via [WaForegroundService]), feeds decrypted messages into
 * the Kortex pipeline via [WaGateway], and exposes connection state (incl. QR codes) for the UI.
 */
class WhatsAppManager(private val context: Context, coordinator: AmbientCoordinator) {

    data class State(
        val status: String = "Not connected",
        val qrCodes: List<String> = emptyList(),
        val paired: Boolean = false,
        val connected: Boolean = false,
    )

    private val db = Room.databaseBuilder(context.applicationContext, WaDatabase::class.java, "wa.db").build()
    private val keyValueStore = RoomKeyValueStore(db.kvDao())
    private val credentialStore = RoomCredentialStore(db.credentialsDao())
    private val gateway = WaGateway(coordinator)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var client: WAClient? = null
    private var service: WaForegroundService? = null

    fun connect() {
        if (client != null) return
        _state.update { it.copy(status = "Connecting…") }
        context.startForegroundService(Intent(context, WaForegroundService::class.java))
    }

    internal fun attachService(s: WaForegroundService) {
        service = s
    }

    internal fun detachService() {
        service = null
    }

    internal suspend fun runClient() {
        val c = WAClient(credentialStore, keyValueStore, listener)
        client = c
        runCatching { c.connect() }.onFailure { e ->
            _state.update { it.copy(status = "Error: ${e.message}") }
            client = null
        }
    }

    private val listener = object : WAClient.Listener {
        override fun onQr(codes: List<String>) {
            _state.update { it.copy(qrCodes = codes, status = "Scan the QR in WhatsApp → Linked devices") }
            service?.updateNotification("Scan QR in WhatsApp Settings → Linked devices")
        }

        override fun onPaired(jid: String) {
            _state.update { it.copy(paired = true, qrCodes = emptyList(), status = "Paired ($jid)") }
            service?.updateNotification("Paired: $jid")
        }

        override fun onLoggedIn() {
            _state.update { it.copy(connected = true, status = "Connected") }
            service?.updateNotification("Connected")
        }

        override fun onMessage(messages: List<MessageDecryptor.Result>) {
            gateway.onMessages(messages)
        }

        override fun onDisconnected(cause: Throwable?) {
            _state.update { it.copy(connected = false, status = "Disconnected${cause?.message?.let { m -> ": $m" } ?: ""}") }
            client = null
            service?.stopSelf()
        }
    }
}
