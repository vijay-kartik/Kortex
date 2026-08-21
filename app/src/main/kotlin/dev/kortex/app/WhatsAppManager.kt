package dev.kortex.app

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.room.Room
import dev.kortex.core.ambient.AmbientCoordinator
import dev.kortex.wa.client.WAClient
import dev.kortex.wa.signal.MessageDecryptor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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
        /** True until we've checked persisted creds — lets the UI avoid flashing onboarding. */
        val initializing: Boolean = true,
        /** Persisted creds existed at launch (returning user) — skip onboarding straight away. */
        val alreadyLinked: Boolean = false,
        /** The linked device JID, shown on the WhatsApp screen so you know which account this is. */
        val deviceJid: String? = null,
        /**
         * One-shot: the first-run gate has been satisfied (linked or skipped). Latched, so logging
         * out from the WhatsApp tab leaves you in the app instead of bouncing to full-screen
         * onboarding mid-session.
         */
        val onboardingDone: Boolean = false,
        /**
         * Incoming messages this session, newest first, capped at [MAX_RECENT]. In memory only —
         * it exists to show what arrived and what the pipeline made of it, while the durable
         * record of anything analyzed already lives in the signal/card stores.
         */
        val recent: List<WaGateway.Received> = emptyList(),
    )

    private val db = Room.databaseBuilder(context.applicationContext, WaDatabase::class.java, "wa.db").build()
    private val keyValueStore = RoomKeyValueStore(db.kvDao())
    private val credentialStore = RoomCredentialStore(db.credentialsDao())
    private val gateway = WaGateway(coordinator, onReceived = ::recordReceived)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    init {
        // Resolve first-run vs. returning user: if a device JID was persisted at pairing, the
        // onboarding gate skips straight to the app and we bring the connection back up. A fresh
        // pairing (this session) is tracked via `paired`/`connected` so the onboarding screen can
        // stay visible until login actually completes.
        scope.launch {
            val jid = runCatching { credentialStore.load()?.deviceJid }.getOrNull()
            _state.update {
                it.copy(
                    initializing = false,
                    alreadyLinked = jid != null,
                    deviceJid = jid,
                    onboardingDone = it.onboardingDone || jid != null,
                    status = if (jid != null) "Linked" else it.status,
                )
            }
            if (jid != null) runCatching { connect() }
        }
    }

    private var client: WAClient? = null
    private var service: WaForegroundService? = null

    fun connect() {
        if (client != null) return
        _state.update { it.copy(status = "Connecting…") }
        context.startForegroundService(Intent(context, WaForegroundService::class.java))
    }

    private fun recordReceived(message: WaGateway.Received) {
        _state.update { it.copy(recent = (listOf(message) + it.recent).take(MAX_RECENT)) }
    }

    /** Mark the first-run gate satisfied without linking ("Skip for now"). */
    fun skipOnboarding() {
        _state.update { it.copy(onboardingDone = true) }
    }

    /**
     * Unlink this device and erase its WhatsApp identity. Destructive and irreversible without a
     * fresh QR scan: the Signal store goes too, because a re-link generates new identity keys and
     * any surviving session would make incoming messages permanently undecryptable.
     */
    fun logout() {
        scope.launch {
            Log.i(TAG, "logout requested")
            _state.update { it.copy(status = "Logging out…") }
            runCatching { client?.logout() }.onFailure { Log.w(TAG, "client logout failed", it) }
            client = null
            service?.stopSelf()
            service = null
            context.stopService(Intent(context, WaForegroundService::class.java))

            runCatching {
                credentialStore.clear()
                keyValueStore.clearAll()
            }.onFailure { Log.w(TAG, "credential wipe failed", it) }

            Log.i(TAG, "logout complete; local WhatsApp state erased")
            // Keep `onboardingDone` latched so we stay in the app rather than reverting to the gate.
            _state.value = State(initializing = false, onboardingDone = true)
        }
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
            _state.update { it.copy(paired = true, qrCodes = emptyList(), deviceJid = jid, status = "Paired ($jid)") }
            service?.updateNotification("Paired: $jid")
        }

        override fun onLoggedIn() {
            _state.update { it.copy(connected = true, onboardingDone = true, status = "Connected") }
            service?.updateNotification("Connected")
        }

        override fun onMessage(messages: List<MessageDecryptor.Result>) {
            scope.launch { gateway.onMessages(messages) }
        }

        override fun onDisconnected(cause: Throwable?) {
            _state.update { it.copy(connected = false, status = "Disconnected${cause?.message?.let { m -> ": $m" } ?: ""}") }
            client = null
            service?.stopSelf()
        }
    }

    private companion object {
        const val TAG = "KortexWA"
        const val MAX_RECENT = 50
    }
}
