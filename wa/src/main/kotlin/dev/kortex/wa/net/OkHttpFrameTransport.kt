package dev.kortex.wa.net

import dev.kortex.wa.noise.NoiseConstants
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * T0 — the WhatsApp WebSocket transport, ported from whatsmeow's FrameSocket.
 *
 * Wire format: each frame is a 3-byte big-endian length prefix + payload, sent as binary
 * WebSocket messages. The 4-byte [NoiseConstants.WA_CONN_HEADER] is prepended exactly once,
 * before the first frame. Incoming data is reassembled because a single WS message may carry
 * several frames, or a frame may span multiple messages.
 */
class OkHttpFrameTransport(
    private val url: String = DEFAULT_URL,
    private val origin: String = DEFAULT_ORIGIN,
    private val client: OkHttpClient = defaultClient(),
) : FrameTransport {

    private val frames = Channel<ByteArray>(Channel.UNLIMITED)
    private val opened = CompletableDeferred<Unit>()
    private val headerSent = AtomicBoolean(false)
    private var webSocket: WebSocket? = null

    // Incoming reassembly state (mutated only from the single WS reader thread).
    private var incoming: ByteArray? = null
    private var incomingLength = 0
    private var receivedLength = 0
    private var partialHeader: ByteArray? = null

    /** Opens the socket; suspends until the server accepts the upgrade (or fails). */
    suspend fun connect() {
        val request = Request.Builder().url(url).header("Origin", origin).build()
        webSocket = client.newWebSocket(request, listener)
        opened.await()
    }

    override suspend fun sendFrame(payload: ByteArray) {
        val ws = webSocket ?: error("transport not connected")
        val header = if (headerSent.compareAndSet(false, true)) NoiseConstants.WA_CONN_HEADER else EMPTY
        val frame = ByteArray(header.size + FRAME_LENGTH_SIZE + payload.size)
        header.copyInto(frame, 0)
        val off = header.size
        frame[off] = (payload.size ushr 16).toByte()
        frame[off + 1] = (payload.size ushr 8).toByte()
        frame[off + 2] = payload.size.toByte()
        payload.copyInto(frame, off + FRAME_LENGTH_SIZE)
        check(ws.send(frame.toByteString())) { "failed to enqueue frame" }
    }

    override suspend fun receiveFrame(): ByteArray = frames.receive()

    fun close() {
        webSocket?.close(1000, null)
        frames.close()
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            opened.complete(Unit)
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            processData(bytes.toByteArray())
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (!opened.isCompleted) opened.completeExceptionally(t)
            frames.close(t)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            frames.close()
        }
    }

    /** Reassemble length-prefixed frames from a stream of WS message chunks. */
    private fun processData(chunk: ByteArray) {
        var msg = chunk
        while (msg.isNotEmpty()) {
            partialHeader?.let {
                msg = it + msg
                partialHeader = null
            }
            val current = incoming
            if (current == null) {
                if (msg.size >= FRAME_LENGTH_SIZE) {
                    val length = ((msg[0].toInt() and 0xFF) shl 16) or
                        ((msg[1].toInt() and 0xFF) shl 8) or
                        (msg[2].toInt() and 0xFF)
                    val rest = msg.copyOfRange(FRAME_LENGTH_SIZE, msg.size)
                    if (rest.size >= length) {
                        frames.trySend(rest.copyOfRange(0, length))
                        msg = rest.copyOfRange(length, rest.size)
                    } else {
                        incoming = ByteArray(length).also { rest.copyInto(it, 0) }
                        incomingLength = length
                        receivedLength = rest.size
                        msg = EMPTY
                    }
                } else {
                    partialHeader = msg
                    msg = EMPTY
                }
            } else {
                val need = incomingLength - receivedLength
                if (msg.size >= need) {
                    msg.copyInto(current, receivedLength, 0, need)
                    frames.trySend(current)
                    incoming = null
                    incomingLength = 0
                    receivedLength = 0
                    msg = msg.copyOfRange(need, msg.size)
                } else {
                    msg.copyInto(current, receivedLength, 0, msg.size)
                    receivedLength += msg.size
                    msg = EMPTY
                }
            }
        }
    }

    companion object {
        const val DEFAULT_URL = "wss://web.whatsapp.com/ws/chat"
        const val DEFAULT_ORIGIN = "https://web.whatsapp.com"
        private const val FRAME_LENGTH_SIZE = 3
        private val EMPTY = ByteArray(0)

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS) // long-lived connection
            .build()
    }
}
