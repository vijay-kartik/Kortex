package dev.kortex.wa.auth

import dev.kortex.wa.crypto.Curve25519
import dev.kortex.wa.crypto.KeyPair25519
import java.security.SecureRandom

/** A signed pre-key: a Curve25519 key pair plus an XEdDSA signature by the identity key. */
data class SignedPreKey(
    val keyId: Int,
    val keyPair: KeyPair25519,
    /** XEdDSA signature over (0x05 || publicKey) by the identity key. */
    val signature: ByteArray,
)

/**
 * The persistent device credentials for the WhatsApp companion (whatsmeow's `store.Device`).
 *
 * Pre-pairing (generated once, [generate]):
 *  - [noiseKey]      — Curve25519 static key for the Noise handshake (client static `s`).
 *  - [identityKey]   — Curve25519 device identity (signs the signed pre-key and, later, the ADV
 *                      device identity).
 *  - [signedPreKey]  — uploaded so other devices can start Signal sessions with us.
 *  - [registrationId]— Signal registration id (14-bit).
 *  - [advSecretKey]  — 32-byte secret used to derive/verify the ADV device identity HMAC.
 *
 * Post-pairing (filled after `pair-success`): [deviceJid], [accountSignedDeviceIdentity], [pushName].
 */
data class DeviceCredentials(
    val noiseKey: KeyPair25519,
    val identityKey: KeyPair25519,
    val signedPreKey: SignedPreKey,
    val registrationId: Int,
    val advSecretKey: ByteArray,
    val deviceJid: String? = null,
    /** Serialized ADVSignedDeviceIdentity returned by the server at pairing time. */
    val accountSignedDeviceIdentity: ByteArray? = null,
    val pushName: String? = null,
) {
    companion object {
        private val random = SecureRandom()

        /** Generate a fresh, unpaired credential set. */
        fun generate(signedPreKeyId: Int = 1): DeviceCredentials {
            val identityKey = Curve25519.generateKeyPair()
            val spkPair = Curve25519.generateKeyPair()
            val spkSignature = Curve25519.sign(
                identityKey.privateKey,
                byteArrayOf(Curve25519.DJB_TYPE) + spkPair.publicKey,
            )
            return DeviceCredentials(
                noiseKey = Curve25519.generateKeyPair(),
                identityKey = identityKey,
                signedPreKey = SignedPreKey(signedPreKeyId, spkPair, spkSignature),
                registrationId = random.nextInt(16380) + 1, // 1..16380
                advSecretKey = ByteArray(32).also(random::nextBytes),
            )
        }
    }
}
