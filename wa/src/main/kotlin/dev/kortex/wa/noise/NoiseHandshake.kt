package dev.kortex.wa.noise

import dev.kortex.wa.crypto.KeyPair25519
import dev.kortex.wa.crypto.X25519
import dev.kortex.wa.net.FrameTransport

/**
 * Drives the WhatsApp `Noise_XX_25519_AESGCM_SHA256` handshake over a [FrameTransport],
 * a faithful port of whatsmeow's `doHandshake`. On success it returns the [NoiseTransport]
 * that encrypts/decrypts all subsequent frames.
 *
 * Sequence (XX):
 *   1. send ClientHello(e)
 *   2. recv ServerHello(e, encrypted s, encrypted cert)
 *   3. mix DH(e_c, e_s); decrypt server static; mix DH(e_c, s_s); decrypt + verify cert
 *   4. encrypt client static; mix DH(s_c, e_s); encrypt clientPayload
 *   5. send ClientFinish(encrypted s, encrypted payload); split → transport keys
 *
 * [clientPayload] (the WAProto ClientPayload for register/login) is produced by the pairing
 * task (T5); for an isolated T2 handshake test it may be a placeholder — the handshake crypto
 * still completes (the server then validates the payload separately).
 */
class NoiseHandshake(
    private val certVerifier: CertVerifier = CertVerifier.Noop,
) {
    suspend fun perform(
        transport: FrameTransport,
        clientStatic: KeyPair25519,
        clientPayload: ByteArray,
    ): NoiseTransport {
        val ephemeral = X25519.generateKeyPair()
        val state = NoiseHandshakeState()

        // 1. ClientHello(e)
        state.start(NoiseConstants.NOISE_START_PATTERN, transport.connectionHeader)
        state.authenticate(ephemeral.publicKey)
        transport.sendFrame(HandshakeMessage.encodeClientHello(ephemeral.publicKey))

        // 2. ServerHello(e, s_enc, cert_enc)
        val serverHello = HandshakeMessage.decodeServerHello(transport.receiveFrame())
        require(serverHello.ephemeral.size == 32) { "bad server ephemeral length" }

        // 3. ee → decrypt static → es → decrypt + verify cert
        state.authenticate(serverHello.ephemeral)
        state.mixSharedSecret(ephemeral.privateKey, serverHello.ephemeral)
        val serverStatic = state.decrypt(serverHello.staticCiphertext)
        require(serverStatic.size == 32) { "bad server static length" }
        state.mixSharedSecret(ephemeral.privateKey, serverStatic)
        val certDecrypted = state.decrypt(serverHello.payload)
        certVerifier.verify(certDecrypted, serverStatic)

        // 4. encrypt client static → se → encrypt payload
        val encryptedStatic = state.encrypt(clientStatic.publicKey)
        state.mixSharedSecret(clientStatic.privateKey, serverHello.ephemeral)
        val encryptedPayload = state.encrypt(clientPayload)

        // 5. ClientFinish → split
        transport.sendFrame(HandshakeMessage.encodeClientFinish(encryptedStatic, encryptedPayload))
        val (writeKey, readKey) = state.split()
        return NoiseTransport(writeKey, readKey)
    }
}
