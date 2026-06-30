package dev.kortex.wa.noise

import dev.kortex.wa.proto.Proto

/**
 * The WAProto `HandshakeMessage` (subset), encoded/decoded by hand since all fields are
 * length-delimited bytes:
 *   HandshakeMessage { ClientHello clientHello = 2; ServerHello serverHello = 3; ClientFinish clientFinish = 4 }
 *   ClientHello/ServerHello { bytes ephemeral = 1; bytes static = 2; bytes payload = 3 }
 *   ClientFinish { bytes static = 1; bytes payload = 2 }
 */
internal object HandshakeMessage {

    fun encodeClientHello(ephemeral: ByteArray): ByteArray {
        val clientHello = Proto.Writer().bytes(1, ephemeral).toByteArray()
        return Proto.Writer().bytes(2, clientHello).toByteArray()
    }

    fun encodeClientFinish(encryptedStatic: ByteArray, encryptedPayload: ByteArray): ByteArray {
        val clientFinish = Proto.Writer()
            .bytes(1, encryptedStatic)
            .bytes(2, encryptedPayload)
            .toByteArray()
        return Proto.Writer().bytes(4, clientFinish).toByteArray()
    }

    data class ServerHello(val ephemeral: ByteArray, val staticCiphertext: ByteArray, val payload: ByteArray)

    fun decodeServerHello(data: ByteArray): ServerHello {
        val outer = Proto.lengthDelimitedFields(data)
        val serverHello = outer[3] ?: error("handshake response missing serverHello (field 3)")
        val inner = Proto.lengthDelimitedFields(serverHello)
        return ServerHello(
            ephemeral = inner[1] ?: error("serverHello missing ephemeral"),
            staticCiphertext = inner[2] ?: error("serverHello missing static"),
            payload = inner[3] ?: error("serverHello missing payload"),
        )
    }
}
