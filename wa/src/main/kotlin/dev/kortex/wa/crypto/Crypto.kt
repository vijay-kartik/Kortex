package dev.kortex.wa.crypto

import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * T1 — cryptographic primitives for the WhatsApp client, the foundation the Noise handshake
 * (T2) and pairing (T5) build on. Everything here is a thin, well-defined wrapper over
 * BouncyCastle (X25519) and the JDK (AES-GCM, HKDF, SHA-256) — no hand-rolled crypto.
 *
 * These map directly to the cipher suite WhatsApp's transport uses:
 * Noise_XX_25519_AESGCM_SHA256.
 */

/** A Curve25519 (X25519) key pair as raw 32-byte values. */
data class KeyPair25519(val privateKey: ByteArray, val publicKey: ByteArray) {
    init {
        require(privateKey.size == X25519.KEY_SIZE) { "private key must be 32 bytes" }
        require(publicKey.size == X25519.KEY_SIZE) { "public key must be 32 bytes" }
    }

    // ByteArray fields → value-based equals/hashCode.
    override fun equals(other: Any?): Boolean =
        this === other || (other is KeyPair25519 &&
            privateKey.contentEquals(other.privateKey) && publicKey.contentEquals(other.publicKey))

    override fun hashCode(): Int = 31 * privateKey.contentHashCode() + publicKey.contentHashCode()
}

/** Curve25519 key generation and Diffie–Hellman (the `DH()` used throughout Noise). */
object X25519 {
    const val KEY_SIZE = 32
    private val random = SecureRandom()

    fun generateKeyPair(): KeyPair25519 {
        val priv = X25519PrivateKeyParameters(random)
        val privBytes = ByteArray(KEY_SIZE).also { priv.encode(it, 0) }
        val pubBytes = ByteArray(KEY_SIZE).also { priv.generatePublicKey().encode(it, 0) }
        return KeyPair25519(privBytes, pubBytes)
    }

    /** Derive the public key for a known private key. */
    fun publicKeyOf(privateKey: ByteArray): ByteArray {
        require(privateKey.size == KEY_SIZE) { "private key must be 32 bytes" }
        val priv = X25519PrivateKeyParameters(privateKey, 0)
        return ByteArray(KEY_SIZE).also { priv.generatePublicKey().encode(it, 0) }
    }

    /** X25519 shared secret between our private key and their public key. */
    fun sharedSecret(privateKey: ByteArray, peerPublicKey: ByteArray): ByteArray {
        require(privateKey.size == KEY_SIZE) { "private key must be 32 bytes" }
        require(peerPublicKey.size == KEY_SIZE) { "public key must be 32 bytes" }
        val agreement = X25519Agreement()
        agreement.init(X25519PrivateKeyParameters(privateKey, 0))
        return ByteArray(agreement.agreementSize).also {
            agreement.calculateAgreement(X25519PublicKeyParameters(peerPublicKey, 0), it, 0)
        }
    }
}

/** AES-256-GCM (Noise cipher function). Tag is 16 bytes, appended to the ciphertext. */
object Aead {
    private const val TAG_BITS = 128

    fun encrypt(key: ByteArray, nonce: ByteArray, associatedData: ByteArray, plaintext: ByteArray): ByteArray {
        val cipher = cipher(Cipher.ENCRYPT_MODE, key, nonce, associatedData)
        return cipher.doFinal(plaintext)
    }

    fun decrypt(key: ByteArray, nonce: ByteArray, associatedData: ByteArray, ciphertext: ByteArray): ByteArray {
        val cipher = cipher(Cipher.DECRYPT_MODE, key, nonce, associatedData)
        return cipher.doFinal(ciphertext)
    }

    private fun cipher(mode: Int, key: ByteArray, nonce: ByteArray, ad: ByteArray): Cipher {
        require(key.size == 32) { "AES-256 key must be 32 bytes" }
        require(nonce.size == 12) { "GCM nonce must be 12 bytes" }
        return Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
            if (ad.isNotEmpty()) updateAAD(ad)
        }
    }
}

/** HKDF over HMAC-SHA256 (RFC 5869) — the Noise `HKDF()` and key-derivation helper. */
object Hkdf {
    private const val HASH_LEN = 32

    fun extract(salt: ByteArray, ikm: ByteArray): ByteArray {
        val effectiveSalt = if (salt.isEmpty()) ByteArray(HASH_LEN) else salt
        return hmac(effectiveSalt, ikm)
    }

    fun expand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length <= 255 * HASH_LEN) { "HKDF length too large" }
        val out = ByteArray(length)
        var t = ByteArray(0)
        var pos = 0
        var counter = 1
        while (pos < length) {
            t = hmac(prk, t + info + byteArrayOf(counter.toByte()))
            val n = minOf(t.size, length - pos)
            t.copyInto(out, pos, 0, n)
            pos += n
            counter++
        }
        return out
    }

    fun derive(salt: ByteArray, ikm: ByteArray, info: ByteArray, length: Int): ByteArray =
        expand(extract(salt, ikm), info, length)

    /** Noise's HKDF(chainingKey, input) → two 32-byte outputs. */
    fun deriveTwo(chainingKey: ByteArray, input: ByteArray): Pair<ByteArray, ByteArray> {
        val okm = derive(chainingKey, input, ByteArray(0), 2 * HASH_LEN)
        return okm.copyOfRange(0, HASH_LEN) to okm.copyOfRange(HASH_LEN, 2 * HASH_LEN)
    }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            doFinal(data)
        }
}

object Sha256 {
    fun hash(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)
}
