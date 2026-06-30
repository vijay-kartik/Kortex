package dev.kortex.wa.crypto

import org.whispersystems.curve25519.Curve25519 as WhisperCurve25519

/**
 * Curve25519 key generation and XEdDSA signatures (whatsmeow's `keys.NewKeyPair` / `Sign`),
 * backed by curve25519-java. This is the signing counterpart to [X25519] (which does DH):
 * generated key pairs are standard Curve25519, so the same key works for both DH and signing.
 *
 * Note WhatsApp signs a public key as `0x05 || pub` (the DJB type byte prefix) — see
 * [dev.kortex.wa.auth.DeviceCredentials].
 */
object Curve25519 {
    /** The Signal/WhatsApp "DJB" curve type byte prefixed before a public key when signing. */
    const val DJB_TYPE: Byte = 0x05

    private val curve = WhisperCurve25519.getInstance(WhisperCurve25519.BEST)

    fun generateKeyPair(): KeyPair25519 {
        val kp = curve.generateKeyPair()
        return KeyPair25519(privateKey = kp.privateKey, publicKey = kp.publicKey)
    }

    /** XEdDSA signature (64 bytes) of [message] under [privateKey]. */
    fun sign(privateKey: ByteArray, message: ByteArray): ByteArray =
        curve.calculateSignature(privateKey, message)

    fun verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean =
        curve.verifySignature(publicKey, message, signature)
}
