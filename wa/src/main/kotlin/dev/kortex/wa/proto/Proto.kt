package dev.kortex.wa.proto

import java.io.ByteArrayOutputStream

/**
 * A tiny protobuf reader/writer — just enough for the Noise handshake messages, whose
 * fields are all length-delimited `bytes`/nested messages. The full WAProto schema set
 * (ClientPayload, Message, …) will be generated with Square Wire in a later task; this keeps
 * T2 self-contained without pulling in the codegen toolchain yet.
 */
internal object Proto {
    private const val WIRE_VARINT = 0
    private const val WIRE_FIXED64 = 1
    private const val WIRE_LEN = 2
    private const val WIRE_FIXED32 = 5

    class Writer {
        private val out = ByteArrayOutputStream()

        /** Write a length-delimited (`bytes` / nested message) field. */
        fun bytes(fieldNumber: Int, value: ByteArray): Writer {
            varint(((fieldNumber shl 3) or WIRE_LEN).toLong())
            varint(value.size.toLong())
            out.write(value)
            return this
        }

        fun toByteArray(): ByteArray = out.toByteArray()

        private fun varint(value: Long) {
            var v = value
            while (true) {
                val b = (v and 0x7F).toInt()
                v = v ushr 7
                if (v != 0L) out.write(b or 0x80) else { out.write(b); break }
            }
        }
    }

    /** Parse top-level fields, returning the last value seen for each length-delimited field. */
    fun lengthDelimitedFields(data: ByteArray): Map<Int, ByteArray> {
        val fields = HashMap<Int, ByteArray>()
        var i = 0
        while (i < data.size) {
            val (tag, afterTag) = varint(data, i)
            i = afterTag
            val field = (tag ushr 3).toInt()
            when ((tag and 0x7).toInt()) {
                WIRE_LEN -> {
                    val (len, afterLen) = varint(data, i)
                    val start = afterLen
                    val end = start + len.toInt()
                    require(end <= data.size) { "truncated length-delimited field" }
                    fields[field] = data.copyOfRange(start, end)
                    i = end
                }
                WIRE_VARINT -> i = varint(data, i).second
                WIRE_FIXED64 -> i += 8
                WIRE_FIXED32 -> i += 4
                else -> error("unsupported wire type in field $field")
            }
        }
        return fields
    }

    private fun varint(data: ByteArray, start: Int): Pair<Long, Int> {
        var shift = 0
        var result = 0L
        var i = start
        while (true) {
            val b = data[i].toInt() and 0xFF
            result = result or ((b and 0x7F).toLong() shl shift)
            i++
            if (b and 0x80 == 0) break
            shift += 7
        }
        return result to i
    }
}
