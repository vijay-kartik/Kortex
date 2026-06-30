package dev.kortex.wa.noise

import dev.kortex.wa.crypto.Aead

/**
 * The post-handshake transport cipher (whatsmeow `NoiseSocket`). Two independent AES-GCM keys
 * with their own monotonic counters; no associated data in transport mode. Frames sent/received
 * after the handshake are encrypted/decrypted here.
 */
class NoiseTransport(
    private val writeKey: ByteArray,
    private val readKey: ByteArray,
) {
    private var writeCounter: Long = 0
    private var readCounter: Long = 0

    fun encrypt(plaintext: ByteArray): ByteArray =
        Aead.encrypt(writeKey, generateIv(writeCounter), EMPTY, plaintext).also { writeCounter++ }

    fun decrypt(ciphertext: ByteArray): ByteArray =
        Aead.decrypt(readKey, generateIv(readCounter), EMPTY, ciphertext).also { readCounter++ }

    private companion object {
        val EMPTY = ByteArray(0)
    }
}
