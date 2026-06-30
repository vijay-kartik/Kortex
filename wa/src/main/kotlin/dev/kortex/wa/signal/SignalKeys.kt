package dev.kortex.wa.signal

import dev.kortex.wa.auth.DeviceCredentials
import org.whispersystems.libsignal.IdentityKey
import org.whispersystems.libsignal.IdentityKeyPair
import org.whispersystems.libsignal.ecc.Curve
import org.whispersystems.libsignal.ecc.ECKeyPair
import org.whispersystems.libsignal.state.SignedPreKeyRecord

/**
 * Adapts Kortex's raw Curve25519 [DeviceCredentials] into libsignal's key types. libsignal
 * encodes public keys with a leading 0x05 "DJB" type byte; private keys are the bare 32 bytes.
 */
object SignalKeys {
    private const val DJB_TYPE: Byte = 0x05

    fun identityKeyPair(credentials: DeviceCredentials): IdentityKeyPair {
        val publicKey = IdentityKey(Curve.decodePoint(prefixed(credentials.identityKey.publicKey), 0))
        val privateKey = Curve.decodePrivatePoint(credentials.identityKey.privateKey)
        return IdentityKeyPair(publicKey, privateKey)
    }

    fun signedPreKeyRecord(credentials: DeviceCredentials): SignedPreKeyRecord {
        val spk = credentials.signedPreKey
        val keyPair = ECKeyPair(
            Curve.decodePoint(prefixed(spk.keyPair.publicKey), 0),
            Curve.decodePrivatePoint(spk.keyPair.privateKey),
        )
        return SignedPreKeyRecord(spk.keyId, System.currentTimeMillis(), keyPair, spk.signature)
    }

    private fun prefixed(publicKey: ByteArray): ByteArray = byteArrayOf(DJB_TYPE) + publicKey
}
