package dev.kortex.wa.client

import android.util.Log
import dev.kortex.wa.auth.AdvSignatures
import dev.kortex.wa.auth.ClientPayloadFactory
import dev.kortex.wa.auth.CredentialStore
import dev.kortex.wa.auth.DeviceCredentials
import dev.kortex.wa.binary.Jid
import dev.kortex.wa.binary.Node
import dev.kortex.wa.binary.WaBinary
import dev.kortex.wa.net.OkHttpFrameTransport
import dev.kortex.wa.noise.NoiseHandshake
import dev.kortex.wa.noise.NoiseTransport
import dev.kortex.wa.noise.WaCertVerifier
import dev.kortex.wa.signal.LidDirectory
import dev.kortex.wa.signal.MessageDecryptor
import dev.kortex.wa.signal.WaSignalStore
import dev.kortex.wa.store.KeyValueStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okio.ByteString.Companion.toByteString
import org.whispersystems.libsignal.util.KeyHelper
import org.whispersystems.libsignal.util.Medium
import proto.ADVDeviceIdentity
import proto.ADVEncryptionType
import proto.ADVSignedDeviceIdentity
import proto.ADVSignedDeviceIdentityHMAC
import java.util.Base64

/**
 * Drives the WhatsApp multi-device connection: Noise handshake → read loop → node routing,
 * including the full QR pairing flow (`pair-device` → `pair-success`) and reconnect-to-login.
 * Faithful port of the relevant parts of whatsmeow's `client.go` + `pair.go`.
 *
 * Lifecycle:
 *  - Unpaired creds → registration handshake → server sends `pair-device` → we emit QR codes →
 *    user scans → `pair-success` → we verify/sign the device identity, persist, ACK, then the
 *    server disconnects and we reconnect with the login payload.
 *  - Paired creds → login handshake → `success`.
 */
class WAClient(
    private val credentialStore: CredentialStore,
    private val keyValueStore: KeyValueStore,
    private val listener: Listener,
    private val transportFactory: () -> OkHttpFrameTransport = { OkHttpFrameTransport() },
) {
    interface Listener {
        /** QR strings to render; rotate through them as earlier ones expire. */
        fun onQr(codes: List<String>)
        fun onPaired(jid: String)
        fun onLoggedIn()
        /** Decrypted incoming messages (DMs/groups). */
        fun onMessage(messages: List<MessageDecryptor.Result>) {}
        fun onNode(node: Node) {}
        fun onDisconnected(cause: Throwable?) {}
    }

    private lateinit var credentials: DeviceCredentials
    private var transport: OkHttpFrameTransport? = null
    private var noise: NoiseTransport? = null
    private var expectReconnect = false
    private var closing = false
    private var ownLid: String? = null
    private val rng = java.security.SecureRandom()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sendMutex = Mutex()
    private var keepAliveJob: Job? = null

    /**
     * IQs we sent, by request id, so the matching response can be named in the log. Without this
     * an error reply to the pre-key upload is indistinguishable from silence — and no published
     * pre-keys means nobody can open a Signal session with us, so no message ever arrives.
     */
    private val lids = LidDirectory(keyValueStore)

    private val pendingIqs = java.util.Collections.synchronizedMap(
        // Bounded: a response we never get (or never match) must not leak. Keepalive pings alone
        // would add an entry every KEEPALIVE_MS for the life of the connection.
        object : LinkedHashMap<String, String>(16, 0.75f, false) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>) = size > MAX_PENDING_IQS
        }
    )

    /** How many retry receipts we've sent per message id — see [sendRetryReceipt]. Bounded likewise. */
    private val retryCounts = java.util.Collections.synchronizedMap(
        object : LinkedHashMap<String, Int>(16, 0.75f, false) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Int>) = size > MAX_PENDING_IQS
        }
    )

    suspend fun connect() {
        credentials = credentialStore.load() ?: DeviceCredentials.generate().also { credentialStore.save(it) }

        val t = transportFactory().also { transport = it }
        t.connect()

        val payload = credentials.deviceJid?.let { jid ->
            val (user, device) = parseJid(jid)
            ClientPayloadFactory.login(user, device)
        } ?: ClientPayloadFactory.registration(credentials)

        Log.i(TAG, "connecting (${if (credentials.deviceJid != null) "login" else "register"})")
        noise = NoiseHandshake(WaCertVerifier).perform(t, credentials.noiseKey, payload)
        Log.i(TAG, "handshake complete")
        startKeepAlive()
        readLoop(t, noise!!)
    }

    private suspend fun readLoop(transport: OkHttpFrameTransport, noise: NoiseTransport) {
        try {
            while (true) {
                val node = WaBinary.unmarshal(noise.decrypt(transport.receiveFrame()))
                route(node)
            }
        } catch (e: CancellationException) {
            keepAliveJob?.cancel()
            throw e
        } catch (e: Exception) {
            keepAliveJob?.cancel()
            // The post-pairing teardown can surface as a clean channel close, a stream:error 515,
            // or a bare TLS close (an SSLException from the read). Reconnect for all of them when a
            // reconnect is expected; only a genuine, unexpected drop is reported as disconnected.
            Log.w(TAG, "read loop ended (closing=$closing expectReconnect=$expectReconnect): ${e.message}")
            if (closing) {
                // A deliberate logout: neither reconnect nor report this as a failure.
                Log.i(TAG, "socket closed by logout")
            } else if (expectReconnect) {
                expectReconnect = false
                connect() // reconnect with the login payload after pairing
            } else {
                listener.onDisconnected(if (e is ClosedReceiveChannelException) null else e)
            }
        }
    }

    private suspend fun route(node: Node) {
        Log.d(TAG, "recv <${node.tag}> ${node.attrs}")
        // Any stanza can be the one that reveals a LID's phone number, so harvest before routing.
        runCatching { lids.record(node) }
        when (node.tag) {
            "iq" -> handleIq(node)
            "success" -> {
                // Our LID: the account's privacy identifier. Incoming stanzas are increasingly
                // addressed by LID rather than phone number, so without this our own fan-out
                // messages read as arriving from a stranger.
                ownLid = node.jidAttr("lid")?.user ?: node.attr("lid")?.substringBefore('@')?.substringBefore(':')
                Log.i(TAG, "success — logged in (lid=$ownLid)")
                // Our own LID/number pairing is never spelled out by a `*_pn` attribute — our own
                // stanzas carry `sender_pn=null` — so seed it from what login already told us.
                // Without it our primary device keeps two Signal sessions (one per addressing)
                // and burns pre-keys it cannot then find.
                val ownPn = credentials.deviceJid?.substringBefore('@')?.substringBefore(':')
                if (ownLid != null && ownPn != null) lids.record(ownLid!!, ownPn, "login")
                runCatching { uploadPreKeysIfNeeded() }
                runCatching { sendActive() }
                listener.onLoggedIn()
            }
            "message" -> handleMessage(node)
            // The server pushes these after login (device/identity/account sync). It waits for our
            // <ack> before it finishes linking, so acking is required to get past "Logging in…".
            "receipt" -> sendAck(node)
            "notification" -> handleNotification(node)
            "call" -> sendAck(node)
            "failure" -> {
                Log.w(TAG, "failure node: ${node.attrs}")
                listener.onDisconnected(IllegalStateException("stream failure: ${node.attr("reason")}"))
            }
            "stream:error" -> handleStreamError(node)
            else -> {
                // No listener implements onNode, so without this line these vanish entirely —
                // <ib> (offline flush), <presence>, <chatstate> and anything new the server adds.
                Log.i(TAG, "unhandled <${node.tag}> ${node.attrs} children=${node.children().map { it.tag }}")
                listener.onNode(node)
            }
        }
    }

    /**
     * `<notification type="encrypt">` is the server telling us the one-time pre-key pool is running
     * low. Republishing keeps new contacts able to open a session with this device; ignoring it
     * means incoming messages quietly stop once the initial [PREKEY_BATCH] is consumed.
     */
    private suspend fun handleNotification(node: Node) {
        sendAck(node)
        if (node.attr("type") != "encrypt") return
        Log.i(TAG, "server requested more pre-keys; republishing")
        keyValueStore.delete(META_NS, KEY_PREKEYS_UPLOADED)
        runCatching { uploadPreKeysIfNeeded() }
            .onFailure { Log.w(TAG, "pre-key republish failed", it) }
    }

    /**
     * Acknowledge a server stanza (whatsmeow `sendAck`): `<ack class=<tag> id=… to=<from> …/>`.
     * Post-link the primary device blocks on these acks, so without them pairing hangs at
     * "Logging in…".
     */
    private suspend fun sendAck(node: Node) {
        val attrs = buildMap<String, Any?> {
            put("class", node.tag)
            node.attrs["id"]?.let { put("id", it) }
            node.attrs["from"]?.let { put("to", it) }
            node.attrs["participant"]?.let { put("participant", it) }
            node.attrs["recipient"]?.let { put("recipient", it) }
            if (node.tag != "message") node.attrs["type"]?.let { put("type", it) }
        }
        runCatching { sendNode(Node("ack", attrs)) }
            .onFailure { Log.w(TAG, "ack failed for <${node.tag}>", it) }
    }

    /**
     * Stream errors (whatsmeow `handleStreamError`). The important one for pairing is `515`:
     * immediately after `pair-success` the server tears down the registration stream and asks us
     * to reconnect — now with the saved login credentials. Treating 515 as fatal is exactly why a
     * freshly scanned QR would pair on the phone but never finish logging in on-device. We close
     * the current socket and let the read loop's [ClosedReceiveChannelException] path reconnect.
     */
    private fun handleStreamError(node: Node) {
        val code = node.attr("code")
        if (code == "515") {
            expectReconnect = true
            transport?.close()
        } else {
            listener.onDisconnected(IllegalStateException("stream error${code?.let { ": $it" }.orEmpty()}"))
        }
    }

    private suspend fun handleMessage(node: Node) {
        val encTypes = node.childrenWithTag("enc").map { it.attr("type") ?: "?" }
        val msgId = node.attr("id")
        if (msgId == null) {
            Log.w(TAG, "msg without id from=${node.attrs["from"]} enc=$encTypes — cannot ack or dedupe")
            return
        }
        Log.i(
            TAG,
            "msg id=$msgId from=${node.attrs["from"]} participant=${node.attrs["participant"]} " +
                "sender_pn=${node.attrs["sender_pn"]} participant_pn=${node.attrs["participant_pn"]} " +
                "type=${node.attr("type")} category=${node.attr("category")} " +
                "offline=${node.attr("offline")} enc=$encTypes",
        )
        sendAck(node)

        if (keyValueStore.get(SEEN_NS, msgId) != null) {
            Log.i(TAG, "msg id=$msgId duplicate, skipping")
            return
        }

        // A message id is only burned once we have actually delivered content from it. WhatsApp
        // announces an incoming message with a metadata-only <message> (no <enc> at all) and sends
        // the encrypted copy a beat later under the SAME id, so treating "nothing to decrypt" as
        // handled would dedupe the real message away — and marking a failed decrypt as seen would
        // make it unrecoverable even after the underlying fault is fixed.
        if (encTypes.isEmpty()) {
            Log.i(TAG, "msg id=$msgId has no enc parts (children=${node.children().map { it.tag }}); awaiting the encrypted copy")
        }

        var failed = false
        var delivered = false
        try {
            val batch = decryptor().decrypt(node)
            batch.failures.forEach { failure ->
                failed = true
                // A missing-pre-key failure on a pkmsg is either genuine batch exhaustion (expect
                // remaining count near 0) or a duplicate delivery of an already-consumed session
                // bootstrap (remaining count still high) — the count is the only way to tell them
                // apart after the fact.
                val remaining = if (failure.encType == "pkmsg") {
                    ", pre-keys remaining=${keyValueStore.keys(KeyValueStore.NS_PREKEY).size}"
                } else ""
                Log.w(TAG, "msg id=$msgId ${failure.encType} decrypt FAILED$remaining — left unseen for retry", failure.cause)
            }
            if (batch.results.isNotEmpty()) {
                Log.i(
                    TAG,
                    "msg id=$msgId decrypted ${batch.results.size} part(s): " +
                        "${batch.results.map { MessageDecryptor.kindOf(it.message) }}",
                )
                delivered = true
                listener.onMessage(batch.results)
            } else if (!failed && encTypes.isNotEmpty()) {
                Log.w(TAG, "msg id=$msgId yielded 0 parts (enc=$encTypes)")
            }
        } catch (e: Exception) {
            failed = true
            Log.w(TAG, "msg id=$msgId decrypt aborted (enc=$encTypes) — left unseen for retry", e)
        }

        if (delivered && !failed) keyValueStore.put(SEEN_NS, msgId, byteArrayOf(1))

        val from = node.jidAttr("from") ?: return
        val participant = node.jidAttr("participant")
        if (failed) {
            // A normal delivery receipt would tell the sender "got it, all good" for a message we
            // couldn't actually read — and without a retry receipt, a sender whose cached session
            // for us has gone stale never learns to refresh, so every future message repeats the
            // same failure forever.
            sendRetryReceipt(msgId, from, participant, node.attr("t"))
        } else {
            sendDeliveryReceipt(msgId, from, participant)
        }
    }

    private fun decryptor() = MessageDecryptor(
        WaSignalStore(credentials, keyValueStore),
        ownUser = credentials.deviceJid?.substringBefore('@')?.substringBefore(':'),
        ownLid = ownLid,
        lids = lids,
    )

    private suspend fun sendDeliveryReceipt(msgId: String, chat: Jid, participant: Jid?) {
        // Pass the JIDs themselves, not their text: the encoder packs a Jid into the binary JID
        // form the server expects, while a String would be sent as an opaque token. It also keeps
        // LID-addressed chats correct now that the decoder reports their real `@lid` server.
        val attrs = buildMap<String, Any?> {
            put("to", chat)
            put("id", msgId)
            put("type", "delivery")
            put("t", (System.currentTimeMillis() / 1000).toString())
            if (participant != null) put("participant", participant)
        }
        runCatching { sendNode(Node("receipt", attrs)) }
    }

    /**
     * Ask the sender to resend a message we failed to decrypt (whatsmeow `sendRetryReceipt`).
     * A confirmed real-world case this covers: the sender's cached session for this device
     * references a one-time pre-key we've already consumed and deleted — every message it builds
     * with that stale reference fails the same way, and without a retry receipt the sender has no
     * signal that anything is wrong, so it never refreshes. The first attempt just asks for a
     * resend, which is usually enough (the sender re-fetches our current — since our fix, always
     * fresh — bundle from the server). From the second attempt on we also embed our current
     * identity/signed-prekey/one fresh one-time pre-key directly in the receipt (`<keys>`), in
     * case the sender's own bundle cache doesn't get invalidated by the request alone.
     */
    private suspend fun sendRetryReceipt(msgId: String, from: Jid, participant: Jid?, originalT: String?) {
        val count = (retryCounts[msgId] ?: 0) + 1
        retryCounts[msgId] = count

        val children = mutableListOf(
            Node(
                "retry",
                mapOf(
                    "count" to count.toString(),
                    "id" to msgId,
                    "t" to (originalT ?: (System.currentTimeMillis() / 1000).toString()),
                    "v" to "1",
                ),
            ),
            Node("registration", content = be32(credentials.registrationId)),
        )
        if (count >= 2) {
            runCatching { keysNode() }
                .onSuccess { children += it }
                .onFailure { Log.w(TAG, "retry: failed to attach a fresh keys bundle for msg id=$msgId", it) }
        }

        val attrs = buildMap<String, Any?> {
            put("to", from)
            put("id", msgId)
            put("type", "retry")
            if (participant != null) put("participant", participant)
        }
        runCatching { sendNode(Node("receipt", attrs, children)) }
            .onSuccess { Log.i(TAG, "sent retry receipt (count=$count) for msg id=$msgId") }
            .onFailure { Log.w(TAG, "retry receipt failed for msg id=$msgId", it) }
    }

    /**
     * One fresh, never-before-issued one-time pre-key plus our identity/signed-prekey, shaped like
     * the `<key>`/`<skey>` nodes in [uploadPreKeysIfNeeded] — bundled into a retry receipt so the
     * sender can rebuild a session immediately rather than waiting on a separate bundle fetch.
     */
    private suspend fun keysNode(): Node {
        val id = nextPreKeyId()
        val record = KeyHelper.generatePreKeys(id, 1).first()
        WaSignalStore(credentials, keyValueStore).storePreKey(record.id, record)
        keyValueStore.put(META_NS, KEY_NEXT_PREKEY_ID, be32(wrapPreKeyId(id + 1)))

        val pub = record.keyPair.publicKey.serialize().copyOfRange(1, 33)
        return Node(
            "keys",
            content = listOf(
                Node("type", content = byteArrayOf(0x05)),
                Node("identity", content = credentials.identityKey.publicKey),
                Node(
                    "key",
                    content = listOf(
                        Node("id", content = be32(record.id).copyOfRange(1, 4)),
                        Node("value", content = pub),
                    ),
                ),
                Node(
                    "skey",
                    content = listOf(
                        Node("id", content = be32(credentials.signedPreKey.keyId).copyOfRange(1, 4)),
                        Node("value", content = credentials.signedPreKey.keyPair.publicKey),
                        Node("signature", content = credentials.signedPreKey.signature),
                    ),
                ),
            ),
        )
    }

    private suspend fun handleIq(node: Node) {
        val id = node.attr("id")
        val label = id?.let { pendingIqs.remove(it) }
        if (label != null) {
            val error = node.child("error")
            when {
                node.attr("type") == "error" || error != null ->
                    Log.w(TAG, "iq $label ERROR ${error?.attrs ?: node.attrs}")
                // Keepalive replies land every KEEPALIVE_MS; only their failures are interesting.
                label == LABEL_PING -> Log.d(TAG, "iq $label result")
                else -> Log.i(TAG, "iq $label result")
            }
            return
        }

        val children = node.children()
        if (children.size != 1 || node.attr("from") != Jid.DEFAULT_USER_SERVER) {
            Log.i(TAG, "unmatched iq ${node.attrs} children=${children.map { it.tag }}")
            return
        }
        when (children[0].tag) {
            "pair-device" -> handlePairDevice(node)
            "pair-success" -> handlePairSuccess(node)
            else -> Log.i(TAG, "unhandled iq <${children[0].tag}> ${node.attrs}")
        }
    }

    /**
     * Unlink this device and tear the connection down deliberately. Mirrors whatsmeow's `Logout`:
     * ask the server to remove the companion (so it disappears from the primary's Linked devices
     * list) before closing. Best-effort — a dead socket must not block the caller's local wipe.
     */
    suspend fun logout() {
        val jid = credentialsOrNull()?.deviceJid
        if (jid != null) {
            runCatching {
                sendNode(
                    Node(
                        "iq",
                        mapOf("to" to SERVER_JID, "type" to "set", "xmlns" to "md", "id" to randomId()),
                        listOf(
                            Node(
                                "remove-companion-device",
                                mapOf("jid" to Jid.parse(jid), "reason" to "user_initiated"),
                            ),
                        ),
                    )
                )
                Log.i(TAG, "sent remove-companion-device for $jid")
            }.onFailure { Log.w(TAG, "unlink request failed (closing anyway): ${it.message}") }
        }
        closing = true
        keepAliveJob?.cancel()
        transport?.close()
        scope.cancel()
    }

    private fun credentialsOrNull(): DeviceCredentials? =
        if (::credentials.isInitialized) credentials else null

    private suspend fun handlePairDevice(node: Node) {
        // Acknowledge the request.
        sendNode(
            Node(
                "iq",
                mapOf("to" to node.attrs["from"], "id" to node.attrs["id"], "type" to "result"),
            )
        )
        val refs = node.child("pair-device")?.childrenWithTag("ref").orEmpty()
        val codes = refs.mapNotNull { it.contentBytes()?.let(::makeQrData) }
        Log.i(TAG, "pair-device: ${codes.size} QR refs")
        listener.onQr(codes)
    }

    /** QR payload (whatsmeow `makeQRData`): url#ref,noisePub,identityPub,advSecret,clientType. */
    private fun makeQrData(ref: ByteArray): String {
        val enc = Base64.getEncoder()
        val refStr = String(ref, Charsets.UTF_8)
        val noisePub = enc.encodeToString(credentials.noiseKey.publicKey)
        val identityPub = enc.encodeToString(credentials.identityKey.publicKey)
        val adv = enc.encodeToString(credentials.advSecretKey)
        // clientType "9" = OtherWebClient. If scanning fails on some WA versions, the older
        // Baileys format is the bare "ref,noisePub,identityPub,adv" without the URL/clientType.
        return "https://wa.me/settings/linked_devices#$refStr,$noisePub,$identityPub,$adv,9"
    }

    private suspend fun handlePairSuccess(node: Node) {
        val reqId = node.attrs["id"] as? String ?: return
        val pairSuccess = node.child("pair-success") ?: return
        val deviceIdentityBytes = pairSuccess.child("device-identity")?.contentBytes()
            ?: return sendPairError(reqId, 500, "internal-error")
        val jid = pairSuccess.child("device")?.jidAttr("jid")?.toString().orEmpty()
        val businessName = pairSuccess.child("biz")?.attr("name")

        Log.i(TAG, "pair-success jid=$jid")
        try {
            handlePair(deviceIdentityBytes, reqId, jid, businessName)
            Log.i(TAG, "paired & signed; awaiting reconnect for login")
            listener.onPaired(jid)
        } catch (e: Exception) {
            Log.w(TAG, "pairing failed", e)
            listener.onDisconnected(e)
        }
    }

    private suspend fun handlePair(deviceIdentityBytes: ByteArray, reqId: String, jid: String, businessName: String?) {
        val container = ADVSignedDeviceIdentityHMAC.ADAPTER.decode(deviceIdentityBytes)
        if (!AdvSignatures.verifyHmac(container, credentials.advSecretKey)) {
            sendPairError(reqId, 401, "hmac-mismatch")
            error("ADV HMAC mismatch")
        }
        val identity = ADVSignedDeviceIdentity.ADAPTER.decode(container.details!!.toByteArray())
        val details = ADVDeviceIdentity.ADAPTER.decode(identity.details!!.toByteArray())
        val hosted = details.deviceType == ADVEncryptionType.HOSTED

        if (!AdvSignatures.verifyAccountSignature(identity, credentials.identityKey.publicKey, hosted)) {
            sendPairError(reqId, 401, "signature-mismatch")
            error("ADV account signature mismatch")
        }

        val deviceSig = AdvSignatures.deviceSignature(
            identity, credentials.identityKey.publicKey, credentials.identityKey.privateKey, hosted,
        )
        val fullySigned = identity.copy(deviceSignature = deviceSig.toByteString())
        val selfSigned = fullySigned.copy(accountSignatureKey = null) // stripped for the reply

        // Persist the paired credentials.
        credentials = credentials.copy(
            deviceJid = jid,
            accountSignedDeviceIdentity = fullySigned.encode(),
            pushName = businessName,
        )
        credentialStore.save(credentials)

        // The server disconnects after this; we'll reconnect with the login payload.
        expectReconnect = true
        sendNode(
            Node(
                "iq",
                mapOf("to" to SERVER_JID, "type" to "result", "id" to reqId),
                listOf(
                    Node(
                        "pair-device-sign",
                        content = listOf(
                            Node(
                                "device-identity",
                                mapOf("key-index" to (details.keyIndex ?: 0)),
                                selfSigned.encode(),
                            ),
                        ),
                    ),
                ),
            )
        )
    }

    private suspend fun sendPairError(id: String, code: Int, text: String) {
        sendNode(
            Node(
                "iq",
                mapOf("to" to SERVER_JID, "type" to "error", "id" to id),
                listOf(Node("error", mapOf("code" to code, "text" to text))),
            )
        )
    }

    private suspend fun sendNode(node: Node) {
        val t = transport ?: error("not connected")
        val n = noise ?: error("handshake not complete")
        // Serialize sends: the Noise cipher uses a per-message nonce counter, so the keepalive
        // pinger and the read loop's receipts/acks must not encrypt concurrently.
        sendMutex.withLock {
            t.sendFrame(n.encrypt(WaBinary.marshal(node)))
        }
    }

    /**
     * WhatsApp closes idle sockets, so mirror whatsmeow's keepalive: an `<iq xmlns="w:p"><ping/>`
     * every [KEEPALIVE_MS]. Without it the server drops the connection while the user is switching
     * to their phone to scan, which shows up as a TLS close (SSLV3_ALERT_CLOSE_NOTIFY).
     */
    private fun startKeepAlive() {
        keepAliveJob?.cancel()
        keepAliveJob = scope.launch {
            while (isActive) {
                delay(KEEPALIVE_MS)
                runCatching { sendPing() }
            }
        }
    }

    private suspend fun sendPing() {
        sendNode(
            Node(
                "iq",
                mapOf("to" to SERVER_JID, "type" to "get", "xmlns" to "w:p", "id" to trackedId(LABEL_PING)),
                listOf(Node("ping")),
            )
        )
    }

    /** A fresh request id, remembered so [handleIq] can name the response it belongs to. */
    private fun trackedId(label: String): String = randomId().also { pendingIqs[it] = label }

    /**
     * whatsmeow's `SetPassive(false)`, sent right after `success`. Without it the freshly linked
     * device never announces itself as active, so the primary device stays stuck on "Logging in…".
     */
    private suspend fun sendActive() {
        sendNode(
            Node(
                "iq",
                mapOf("to" to SERVER_JID, "type" to "set", "xmlns" to "passive", "id" to trackedId(LABEL_ACTIVE)),
                listOf(Node("active")),
            )
        )
    }

    /**
     * After login, publish one-time pre-keys so contacts can start Signal sessions with us.
     * Called both on first login and again whenever the server asks for a top-up
     * ([handleNotification]) — each call MUST mint ids that have never been issued before.
     * One-time pre-keys are deleted the instant they're consumed (that's the whole point of
     * "one-time"), so regenerating the same id range on a top-up would silently overwrite a
     * still-outstanding, not-yet-used key: a sender holding a cached bundle for that id would
     * then decrypt with the wrong keypair. [nextPreKeyId] persists across calls specifically to
     * rule that out.
     */
    private suspend fun uploadPreKeysIfNeeded() {
        if (keyValueStore.get(META_NS, KEY_PREKEYS_UPLOADED) != null) return
        val store = WaSignalStore(credentials, keyValueStore)
        val startId = nextPreKeyId()
        val preKeys = KeyHelper.generatePreKeys(startId, PREKEY_BATCH)
        preKeys.forEach { store.storePreKey(it.id, it) }
        keyValueStore.put(META_NS, KEY_NEXT_PREKEY_ID, be32(wrapPreKeyId(startId + PREKEY_BATCH)))
        Log.i(TAG, "publishing pre-keys $startId..${startId + PREKEY_BATCH - 1}")

        val listNodes = preKeys.map { record ->
            // libsignal serializes public keys as 0x05||pub; the wire format wants the raw 32 bytes.
            val pub = record.keyPair.publicKey.serialize().copyOfRange(1, 33)
            Node(
                "key",
                content = listOf(
                    Node("id", content = be32(record.id).copyOfRange(1, 4)),
                    Node("value", content = pub),
                ),
            )
        }
        val signedNode = Node(
            "skey",
            content = listOf(
                Node("id", content = be32(credentials.signedPreKey.keyId).copyOfRange(1, 4)),
                Node("value", content = credentials.signedPreKey.keyPair.publicKey),
                Node("signature", content = credentials.signedPreKey.signature),
            ),
        )

        sendNode(
            Node(
                "iq",
                mapOf("to" to SERVER_JID, "type" to "set", "xmlns" to "encrypt", "id" to trackedId(LABEL_PREKEYS)),
                listOf(
                    Node("registration", content = be32(credentials.registrationId)),
                    Node("type", content = byteArrayOf(0x05)),
                    Node("identity", content = credentials.identityKey.publicKey),
                    Node("list", content = listNodes),
                    signedNode,
                ),
            )
        )
        keyValueStore.put(META_NS, KEY_PREKEYS_UPLOADED, byteArrayOf(1))
    }

    private fun be32(value: Int): ByteArray = byteArrayOf(
        (value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte(),
    )

    private fun fromBe32(bytes: ByteArray): Int =
        ((bytes[0].toInt() and 0xFF) shl 24) or ((bytes[1].toInt() and 0xFF) shl 16) or
            ((bytes[2].toInt() and 0xFF) shl 8) or (bytes[3].toInt() and 0xFF)

    /** The next never-before-issued pre-key id. 1 on a device's very first upload. */
    private fun nextPreKeyId(): Int = keyValueStore.get(META_NS, KEY_NEXT_PREKEY_ID)?.let(::fromBe32) ?: 1

    /** Pre-key ids are libsignal `Medium` values (24-bit); wrap rather than overflow. Id 0 is
     *  reserved by convention, so wrapping lands back on 1, not 0. */
    private fun wrapPreKeyId(id: Int): Int = if (id >= Medium.MAX_VALUE) 1 else id

    private fun randomId(): String = buildString { repeat(16) { append(HEX[rng.nextInt(HEX.length)]) } }

    /** Parse "user[:device]@server" → (user, device). */
    private fun parseJid(jid: String): Pair<Long, Int> {
        val local = jid.substringBefore('@')
        val user = local.substringBefore(':').substringBefore('_').toLongOrNull() ?: 0L
        val device = local.substringAfter(':', "0").toIntOrNull() ?: 0
        return user to device
    }

    private companion object {
        val SERVER_JID = Jid(user = "", server = Jid.DEFAULT_USER_SERVER)
        const val META_NS = "wa_meta"
        const val KEY_PREKEYS_UPLOADED = "prekeys_uploaded"
        const val KEY_NEXT_PREKEY_ID = "next_prekey_id"
        const val PREKEY_BATCH = 30
        const val HEX = "0123456789abcdef"
        const val SEEN_NS = "wa_seen"
        const val LABEL_PING = "ping"
        const val LABEL_ACTIVE = "active"
        const val LABEL_PREKEYS = "prekey-upload"
        const val KEEPALIVE_MS = 20_000L
        const val MAX_PENDING_IQS = 32
        const val TAG = "KortexWA"
    }
}
