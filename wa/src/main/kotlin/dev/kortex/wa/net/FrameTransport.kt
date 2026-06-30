package dev.kortex.wa.net

import dev.kortex.wa.noise.NoiseConstants

/**
 * The framed byte transport the handshake runs over (the OkHttp WebSocket implementation is
 * T0). Frames are length-prefixed (3-byte big-endian); the [connectionHeader] is sent once
 * before the first frame and is also mixed into the Noise hash as the prologue.
 */
interface FrameTransport {
    /** WAConnHeader — prologue for the handshake and the first-frame prefix. */
    val connectionHeader: ByteArray
        get() = NoiseConstants.WA_CONN_HEADER

    suspend fun sendFrame(payload: ByteArray)
    suspend fun receiveFrame(): ByteArray
}
