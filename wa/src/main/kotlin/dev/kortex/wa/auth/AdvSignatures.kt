package dev.kortex.wa.auth

import dev.kortex.wa.crypto.Curve25519
import okio.ByteString.Companion.toByteString
import proto.ADVEncryptionType
import proto.ADVSignedDeviceIdentity
import proto.ADVSignedDeviceIdentityHMAC
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The ADV (account/device verification) cryptography used during `pair-success`, ported from
 * whatsmeow's `pair.go`. Confirms the device identity the server sent is genuinely signed by
 * the user's primary device, then produces this companion's own device signature.
 *
 * Signature message prefixes (whatsmeow):
 *   account = {6,0}  (hosted {6,5});  device = {6,1}  (hosted {6,6})
 */
object AdvSignatures {
    private val ACCOUNT_PREFIX = byteArrayOf(6, 0)
    private val DEVICE_PREFIX = byteArrayOf(6, 1)
    private val HOSTED_ACCOUNT_PREFIX = byteArrayOf(6, 5)
    private val HOSTED_DEVICE_PREFIX = byteArrayOf(6, 6)

    /** Verify the HMAC over the signed-identity details using the shared ADV secret. */
    fun verifyHmac(container: ADVSignedDeviceIdentityHMAC, advSecret: ByteArray): Boolean {
        val details = container.details?.toByteArray() ?: return false
        val expected = container.hmac?.toByteArray() ?: return false
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(advSecret, "HmacSHA256")) }
        if (container.accountType == ADVEncryptionType.HOSTED) mac.update(HOSTED_ACCOUNT_PREFIX)
        mac.update(details)
        return MessageDigest.isEqual(mac.doFinal(), expected)
    }

    /** Verify the primary account's signature over our identity (key, details). */
    fun verifyAccountSignature(
        identity: ADVSignedDeviceIdentity,
        ourIdentityPublicKey: ByteArray,
        hosted: Boolean,
    ): Boolean {
        val accountKey = identity.accountSignatureKey?.toByteArray() ?: return false
        val signature = identity.accountSignature?.toByteArray() ?: return false
        val details = identity.details?.toByteArray() ?: return false
        if (accountKey.size != 32 || signature.size != 64) return false
        val prefix = if (hosted) HOSTED_ACCOUNT_PREFIX else ACCOUNT_PREFIX
        val message = prefix + details + ourIdentityPublicKey
        return Curve25519.verify(accountKey, message, signature)
    }

    /** Produce this device's signature, proving we accept the identity the account signed. */
    fun deviceSignature(
        identity: ADVSignedDeviceIdentity,
        ourIdentityPublicKey: ByteArray,
        ourIdentityPrivateKey: ByteArray,
        hosted: Boolean,
    ): ByteArray {
        val details = identity.details?.toByteArray() ?: error("missing details")
        val accountKey = identity.accountSignatureKey?.toByteArray() ?: error("missing account signature key")
        val prefix = if (hosted) HOSTED_DEVICE_PREFIX else DEVICE_PREFIX
        val message = prefix + details + ourIdentityPublicKey + accountKey
        return Curve25519.sign(ourIdentityPrivateKey, message)
    }
}
