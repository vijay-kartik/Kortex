package dev.kortex.wa.client

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
import dev.kortex.wa.signal.MessageDecryptor
import dev.kortex.wa.signal.WaSignalStore
import dev.kortex.wa.store.KeyValueStore
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import okio.ByteString.Companion.toByteString
import org.whispersystems.libsignal.util.KeyHelper
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
    private val rng = java.security.SecureRandom()

    suspend fun connect() {
        credentials = credentialStore.load() ?: DeviceCredentials.generate().also { credentialStore.save(it) }

        val t = transportFactory().also { transport = it }
        t.connect()

        val payload = credentials.deviceJid?.let { jid ->
            val (user, device) = parseJid(jid)
            ClientPayloadFactory.login(user, device)
        } ?: ClientPayloadFactory.registration(credentials)

        noise = NoiseHandshake(WaCertVerifier).perform(t, credentials.noiseKey, payload)
        readLoop(t, noise!!)
    }

    private suspend fun readLoop(transport: OkHttpFrameTransport, noise: NoiseTransport) {
        try {
            while (true) {
                val node = WaBinary.unmarshal(noise.decrypt(transport.receiveFrame()))
                route(node)
            }
        } catch (e: ClosedReceiveChannelException) {
            if (expectReconnect) {
                expectReconnect = false
                connect() // reconnect with the login payload after pairing
            } else {
                listener.onDisconnected(null)
            }
        } catch (e: Exception) {
            listener.onDisconnected(e)
        }
    }

    private suspend fun route(node: Node) {
        when (node.tag) {
            "iq" -> handleIq(node)
            "success" -> {
                runCatching { uploadPreKeysIfNeeded() }
                listener.onLoggedIn()
            }
            "message" -> handleMessage(node)
            "failure" -> listener.onDisconnected(IllegalStateException("stream failure: ${node.attr("reason")}"))
            "stream:error" -> listener.onDisconnected(IllegalStateException("stream error"))
            else -> listener.onNode(node)
        }
    }

    private suspend fun handleMessage(node: Node) {
        val msgId = node.attr("id") ?: return

        // Deduplication check
        if (keyValueStore.get(SEEN_NS, msgId) != null) return

        val results = runCatching {
            MessageDecryptor(WaSignalStore(credentials, keyValueStore)).decrypt(node)
        }.getOrDefault(emptyList())

        keyValueStore.put(SEEN_NS, msgId, byteArrayOf(1))
        if (results.isNotEmpty()) listener.onMessage(results)

        // Send delivery receipt
        val from = node.jidAttr("from") ?: return
        val participant = node.jidAttr("participant")
        sendDeliveryReceipt(msgId, from, participant)
    }

    private suspend fun sendDeliveryReceipt(msgId: String, chat: Jid, participant: Jid?) {
        val attrs = buildMap<String, Any?> {
            put("to", chat.toString())
            put("id", msgId)
            put("type", "delivery")
            put("t", (System.currentTimeMillis() / 1000).toString())
            if (participant != null) put("participant", participant.toString())
        }
        runCatching { sendNode(Node("receipt", attrs)) }
    }

    private suspend fun handleIq(node: Node) {
        val children = node.children()
        if (children.size != 1 || node.attr("from") != Jid.DEFAULT_USER_SERVER) return
        when (children[0].tag) {
            "pair-device" -> handlePairDevice(node)
            "pair-success" -> handlePairSuccess(node)
        }
    }

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

        try {
            handlePair(deviceIdentityBytes, reqId, jid, businessName)
            listener.onPaired(jid)
        } catch (e: Exception) {
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
        t.sendFrame(n.encrypt(WaBinary.marshal(node)))
    }

    /** After login, publish one-time pre-keys so contacts can start Signal sessions with us. */
    private suspend fun uploadPreKeysIfNeeded() {
        if (keyValueStore.get(META_NS, KEY_PREKEYS_UPLOADED) != null) return
        val store = WaSignalStore(credentials, keyValueStore)
        val preKeys = KeyHelper.generatePreKeys(1, PREKEY_BATCH)
        preKeys.forEach { store.storePreKey(it.id, it) }

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
                mapOf("to" to SERVER_JID, "type" to "set", "xmlns" to "encrypt", "id" to randomId()),
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
        const val PREKEY_BATCH = 30
        const val HEX = "0123456789abcdef"
        const val SEEN_NS = "wa_seen"
    }
}
