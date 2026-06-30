package dev.kortex.wa.noise

/**
 * Exact transport constants, ported verbatim from whatsmeow (`socket/constants.go`,
 * `socket/noisehandshake.go`). Do not "tidy" these — they must match the server.
 */
object NoiseConstants {
    /** Noise pattern string + 4 NUL bytes = exactly 32 bytes, used directly as the initial hash. */
    val NOISE_START_PATTERN: ByteArray =
        "Noise_XX_25519_AESGCM_SHA256".toByteArray(Charsets.US_ASCII) + ByteArray(4)

    /** WAConnHeader = {'W','A', WAMagicValue=6, DictVersion=3}. Prologue + first-frame header. */
    val WA_CONN_HEADER: ByteArray = byteArrayOf('W'.code.toByte(), 'A'.code.toByte(), 6, 3)
}

/**
 * Noise nonce/IV: 12 bytes, all zero except a big-endian uint32 counter in the last 4 bytes
 * (whatsmeow `generateIV`). Counter is post-incremented by the caller.
 */
internal fun generateIv(counter: Long): ByteArray = ByteArray(12).apply {
    this[8] = (counter ushr 24).toByte()
    this[9] = (counter ushr 16).toByte()
    this[10] = (counter ushr 8).toByte()
    this[11] = counter.toByte()
}
