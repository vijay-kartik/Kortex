package dev.kortex.wa.binary

import java.io.ByteArrayOutputStream
import java.util.zip.Inflater

/**
 * Public entry points for WhatsApp binary-XML, matching whatsmeow's Marshal/Unpack.
 *
 * Decrypted transport frames are unmarshalled here into [Node]s; outgoing nodes are marshalled
 * and then encrypted by the Noise transport. [marshal] output already carries the leading
 * uncompressed-flags byte (so it can be sent directly); [unmarshal] strips that byte and
 * zlib-inflates if the flag bit is set.
 */
object WaBinary {

    fun marshal(node: Node): ByteArray = BinaryEncoder().apply { writeNode(node) }.toByteArray()

    fun unmarshal(data: ByteArray): Node {
        require(data.isNotEmpty()) { "empty binary node" }
        return BinaryDecoder(unpack(data)).readNode()
    }

    /** Strip the flags byte; bit 1 (value 2) means the remainder is zlib-compressed. */
    fun unpack(data: ByteArray): ByteArray {
        require(data.isNotEmpty()) { "empty data" }
        val flags = data[0].toInt()
        val body = data.copyOfRange(1, data.size)
        return if (flags and 2 != 0) inflate(body) else body
    }

    private fun inflate(data: ByteArray): ByteArray {
        val inflater = Inflater()
        inflater.setInput(data)
        val out = ByteArrayOutputStream(data.size * 2)
        val buffer = ByteArray(8192)
        try {
            while (!inflater.finished()) {
                val n = inflater.inflate(buffer)
                if (n == 0 && inflater.needsInput()) break
                out.write(buffer, 0, n)
            }
        } finally {
            inflater.end()
        }
        return out.toByteArray()
    }
}
