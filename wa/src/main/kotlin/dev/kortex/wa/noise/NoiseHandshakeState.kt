package dev.kortex.wa.noise

import dev.kortex.wa.crypto.Aead
import dev.kortex.wa.crypto.Hkdf
import dev.kortex.wa.crypto.Sha256
import dev.kortex.wa.crypto.X25519

/**
 * The Noise symmetric state for `Noise_XX_25519_AESGCM_SHA256`, a direct port of whatsmeow's
 * `NoiseHandshake`. Tracks the running hash `h`, the chaining salt `ck`, the current AEAD key,
 * and a nonce counter.
 *
 * Sequencing notes that must hold exactly:
 *  - [start]: h = pattern (32 bytes), salt = h, key = h, then authenticate(header).
 *  - [encrypt]/[decrypt]: AES-GCM with nonce = generateIv(counter++) and AAD = current hash,
 *    then the ciphertext is mixed into the hash.
 *  - [mixIntoKey]: counter resets to 0; HKDF(salt, input) → (newSalt, newKey).
 */
internal class NoiseHandshakeState {
    private var hash: ByteArray = ByteArray(0)
    private var salt: ByteArray = ByteArray(0)
    private var key: ByteArray = ByteArray(0)
    private var counter: Long = 0

    fun start(pattern: ByteArray, header: ByteArray) {
        hash = if (pattern.size == 32) pattern else Sha256.hash(pattern)
        salt = hash
        key = hash
        authenticate(header)
    }

    fun authenticate(data: ByteArray) {
        hash = Sha256.hash(hash + data)
    }

    fun encrypt(plaintext: ByteArray): ByteArray {
        val ciphertext = Aead.encrypt(key, nextNonce(), hash, plaintext)
        authenticate(ciphertext)
        return ciphertext
    }

    fun decrypt(ciphertext: ByteArray): ByteArray {
        val plaintext = Aead.decrypt(key, nextNonce(), hash, ciphertext)
        authenticate(ciphertext)
        return plaintext
    }

    fun mixSharedSecret(privateKey: ByteArray, peerPublicKey: ByteArray) {
        mixIntoKey(X25519.sharedSecret(privateKey, peerPublicKey))
    }

    fun mixIntoKey(input: ByteArray) {
        counter = 0
        val (write, read) = Hkdf.deriveTwo(salt, input)
        salt = write
        key = read
    }

    /** Final split into (writeKey, readKey) for the transport cipher. */
    fun split(): Pair<ByteArray, ByteArray> = Hkdf.deriveTwo(salt, ByteArray(0))

    private fun nextNonce(): ByteArray = generateIv(counter).also { counter++ }
}
