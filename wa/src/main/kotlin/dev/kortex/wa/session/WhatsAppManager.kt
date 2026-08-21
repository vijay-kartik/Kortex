package dev.kortex.wa.session

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.room.Room
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
 * Owner of the native WhatsApp connection: builds the Room-backed stores, runs the [WAClient] on
 * a background scope (via [WaForegroundService]), tracks what has been observed, and exposes
 * connection state (incl. QR codes) for the UI.
 *
 * This is the whole "login and message observing" surface — QR pairing, connect/reconnect,
 * logout, and a running log of messages seen on the connection — with no dependency on any
 * particular app's pipeline. A host app hands [onMessages] a callback to do something with
 * decrypted messages (feed them into its own agent/pipeline, store them, whatever); it can later
 * call [annotate] to attach a note to a message already shown (e.g. what its pipeline decided),
 * without this module needing to know what that pipeline is.
 */
class WhatsAppManager(
    private val context: Context,
    private val onMessages: suspend (List<Received>) -> Unit = {},
) {

    /**
     * One message seen on this connection.
     *
     * Deliberately a plain data class rather than the decrypted protobuf: the generated WAProto
     * types are an implementation detail of this module, and handing them to callers would put
     * 200+ generated classes and the Wire runtime into every consumer's compile classpath.
     */
    data class Received(
        val id: String,
        /** The other party in the chat: who sent it, or who we sent it to. */
        val phone: String?,
        /** Message body, or null when the payload carries no text (media, reactions, …). */
        val text: String?,
        /** Which `Message` field carried the payload, e.g. `conversation`, `imageMessage`. */
        val kind: String,
        val timestampMillis: Long,
        /** True for messages we sent from another device, mirrored here. */
        val fromMe: Boolean,
        /** Sender JID, as text — for logging and correlation with the wire trace. */
        val senderJid: String,
        /** Chat JID, as text: the peer for a DM, the group for a group message. */
        val chatJid: String,
        /** A host app's note about this message, attached later via [annotate]. */
        val annotation: Annotation? = null,
    )

    /** A host app's opaque note on a [Received] message — this module attaches no meaning to it. */
    data class Annotation(val label: String, val isError: Boolean = false)

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
         * Messages seen this session, newest first, capped at [MAX_RECENT]. In memory only — a
         * host app that wants durable storage persists what it needs from [onMessages] itself.
         */
        val recent: List<Received> = emptyList(),
    )

    private val db = Room.databaseBuilder(context.applicationContext, WaDatabase::class.java, "wa.db").build()
    private val keyValueStore = RoomKeyValueStore(db.kvDao())
    private val credentialStore = RoomCredentialStore(db.credentialsDao())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    init {
        active = this
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

    /** Mark the first-run gate satisfied without linking ("Skip for now"). */
    fun skipOnboarding() {
        _state.update { it.copy(onboardingDone = true) }
    }

    /** Attach [annotation] to the message [id], e.g. what a host app's pipeline decided about it. */
    fun annotate(id: String, annotation: Annotation) {
        _state.update { st -> st.copy(recent = st.recent.map { if (it.id == id) it.copy(annotation = annotation) else it }) }
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

    /**
     * Map a decrypted result to the public [Received] shape, dropping device-to-device plumbing.
     * Returns null for anything a caller should never see, so the protocol-traffic rule lives in
     * one place instead of being re-implemented by every consumer.
     */
    private fun toReceived(result: MessageDecryptor.Result): Received? {
        if (MessageDecryptor.isProtocolTraffic(result)) {
            Log.i(TAG, "id=${result.id} is protocol traffic (category=${result.category}), not surfaced")
            return null
        }
        return Received(
            id = result.id,
            phone = if (result.fromMe) result.recipientPhone else result.senderPhone,
            text = MessageDecryptor.textOf(result.message),
            kind = MessageDecryptor.kindOf(result.message),
            timestampMillis = result.timestampMillis,
            fromMe = result.fromMe,
            senderJid = result.sender.toString(),
            chatJid = result.chat.toString(),
        )
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
            val received = messages.mapNotNull(::toReceived)
            if (received.isEmpty()) return
            _state.update { it.copy(recent = (received.asReversed() + it.recent).take(MAX_RECENT)) }
            scope.launch { onMessages(received) }
        }

        override fun onDisconnected(cause: Throwable?) {
            _state.update { it.copy(connected = false, status = "Disconnected${cause?.message?.let { m -> ": $m" } ?: ""}") }
            client = null
            service?.stopSelf()
        }
    }

    companion object {
        private const val TAG = "KortexWA"
        private const val MAX_RECENT = 50

        /**
         * The process's active manager, so [WaForegroundService] — which Android instantiates via
         * the manifest, not a caller — can reach it without depending on a host app's DI container.
         * There is exactly one WhatsApp connection per process, so a single slot is sufficient.
         */
        @Volatile private var active: WhatsAppManager? = null

        internal fun current(): WhatsAppManager? = active
    }
}
