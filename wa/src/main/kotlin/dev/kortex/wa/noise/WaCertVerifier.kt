package dev.kortex.wa.noise

import dev.kortex.wa.crypto.Curve25519
import proto.CertChain

/**
 * Real WhatsApp Noise certificate verification (whatsmeow `verifyServerCert`): authenticates
 * the server by checking the intermediate + leaf certificate signatures against the pinned
 * [CertVerifier.WA_CERT_PUB_KEY], the issuer-serial chain, and that the leaf's key equals the
 * server static decrypted during the handshake.
 */
object WaCertVerifier : CertVerifier {
    private const val WA_CERT_ISSUER_SERIAL = 0

    override fun verify(certDecrypted: ByteArray, serverStatic: ByteArray) {
        val chain = CertChain.ADAPTER.decode(certDecrypted)
        val intermediate = chain.intermediate ?: error("missing intermediate cert")
        val leaf = chain.leaf ?: error("missing leaf cert")

        val intermediateDetailsRaw = intermediate.details?.toByteArray() ?: error("missing intermediate details")
        val intermediateSignature = intermediate.signature?.toByteArray() ?: error("missing intermediate signature")
        val leafDetailsRaw = leaf.details?.toByteArray() ?: error("missing leaf details")
        val leafSignature = leaf.signature?.toByteArray() ?: error("missing leaf signature")

        require(intermediateSignature.size == 64) { "bad intermediate signature length" }
        require(leafSignature.size == 64) { "bad leaf signature length" }
        require(Curve25519.verify(CertVerifier.WA_CERT_PUB_KEY, intermediateDetailsRaw, intermediateSignature)) {
            "intermediate cert signature verification failed"
        }

        val intermediateDetails = CertChain.NoiseCertificate.Details.ADAPTER.decode(intermediateDetailsRaw)
        require((intermediateDetails.issuerSerial ?: -1) == WA_CERT_ISSUER_SERIAL) { "bad intermediate issuer serial" }
        val intermediateKey = intermediateDetails.key?.toByteArray() ?: error("missing intermediate key")
        require(intermediateKey.size == 32) { "bad intermediate key length" }

        require(Curve25519.verify(intermediateKey, leafDetailsRaw, leafSignature)) {
            "leaf cert signature verification failed"
        }

        val leafDetails = CertChain.NoiseCertificate.Details.ADAPTER.decode(leafDetailsRaw)
        require(leafDetails.issuerSerial == intermediateDetails.serial) { "leaf issuer serial mismatch" }
        val leafKey = leafDetails.key?.toByteArray() ?: error("missing leaf key")
        require(leafKey.contentEquals(serverStatic)) { "cert key doesn't match decrypted static" }
    }
}
