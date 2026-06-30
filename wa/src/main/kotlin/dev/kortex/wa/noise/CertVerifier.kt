package dev.kortex.wa.noise

/**
 * Verifies the server's Noise certificate (decrypted during the handshake) against WhatsApp's
 * pinned root key — i.e. confirms we're talking to real WhatsApp servers.
 *
 * The full check (parse waCert.CertChain, verify intermediate+leaf Curve25519/XEdDSA signatures
 * against WACertPubKey, validity dates, and leafKey == decrypted server static) needs a
 * Curve25519 signature verifier; that arrives with libsignal in a later task. Until then the
 * [Noop] verifier lets the handshake complete but does NOT authenticate the server — must be
 * replaced before this is trusted on real traffic.
 */
fun interface CertVerifier {
    /** @throws IllegalStateException if the certificate is invalid. */
    fun verify(certDecrypted: ByteArray, serverStatic: ByteArray)

    companion object {
        /** WhatsApp's pinned cert root public key (whatsmeow WACertPubKey). */
        val WA_CERT_PUB_KEY: ByteArray = byteArrayOf(
            0x14, 0x23, 0x75, 0x57, 0x4d, 0x0a, 0x58, 0x71,
            0x66, 0xaa.toByte(), 0xe7.toByte(), 0x1e, 0xbe.toByte(), 0x51, 0x64, 0x37,
            0xc4.toByte(), 0xa2.toByte(), 0x8b.toByte(), 0x73, 0xe3.toByte(), 0x69, 0x5c, 0x6c,
            0xe1.toByte(), 0xf7.toByte(), 0xf9.toByte(), 0x54, 0x5d, 0xa8.toByte(), 0xee.toByte(), 0x6b,
        )

        /** TEMPORARY: completes the handshake without authenticating the server. Replace ASAP. */
        val Noop = CertVerifier { _, _ -> /* TODO(T7): real cert-chain verification via libsignal */ }
    }
}
