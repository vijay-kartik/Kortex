package dev.kortex.wa.auth

import dev.kortex.wa.crypto.Curve25519
import okio.ByteString.Companion.toByteString
import proto.ClientPayload
import proto.DeviceProps
import java.security.MessageDigest

/**
 * Builds the WAProto `ClientPayload` sent (encrypted) as the final Noise handshake message —
 * a registration payload when first pairing, or a login payload on subsequent connects.
 * Mirrors whatsmeow's `getRegistrationPayload` / `getLoginPayload` and `BaseClientPayload`.
 *
 * The WA web client version is version-sensitive: if WhatsApp rejects login, bump [WA_VERSION]
 * to the current web version (and re-check the protobuf schema).
 */
object ClientPayloadFactory {

    /** Current WhatsApp web client version (whatsmeow `waVersion`). */
    val WA_VERSION = intArrayOf(2, 3000, 1041871181)

    /** Registration payload — used during QR pairing (passive=false, carries device keys). */
    fun registration(credentials: DeviceCredentials): ByteArray = ClientPayload(
        userAgent = baseUserAgent(),
        webInfo = ClientPayload.WebInfo(webSubPlatform = ClientPayload.WebInfo.WebSubPlatform.WEB_BROWSER),
        connectType = ClientPayload.ConnectType.WIFI_UNKNOWN,
        connectReason = ClientPayload.ConnectReason.USER_ACTIVATED,
        passive = false,
        pull = false,
        devicePairingData = ClientPayload.DevicePairingRegistrationData(
            eRegid = be32(credentials.registrationId).toByteString(),
            eKeytype = byteArrayOf(Curve25519.DJB_TYPE).toByteString(),
            eIdent = credentials.identityKey.publicKey.toByteString(),
            // 3-byte big-endian key id (whatsmeow drops the high byte of the 4-byte value).
            eSkeyId = be32(credentials.signedPreKey.keyId).copyOfRange(1, 4).toByteString(),
            eSkeyVal = credentials.signedPreKey.keyPair.publicKey.toByteString(),
            eSkeySig = credentials.signedPreKey.signature.toByteString(),
            buildHash = versionHash().toByteString(),
            deviceProps = deviceProps().encode().toByteString(),
        ),
    ).encode()

    /** Login payload — used on reconnect with the device JID assigned at pairing (passive=true). */
    fun login(username: Long, device: Int): ByteArray = ClientPayload(
        userAgent = baseUserAgent(),
        webInfo = ClientPayload.WebInfo(webSubPlatform = ClientPayload.WebInfo.WebSubPlatform.WEB_BROWSER),
        connectType = ClientPayload.ConnectType.WIFI_UNKNOWN,
        connectReason = ClientPayload.ConnectReason.USER_ACTIVATED,
        passive = true,
        username = username,
        device = device,
    ).encode()

    private fun baseUserAgent() = ClientPayload.UserAgent(
        platform = ClientPayload.UserAgent.Platform.WEB,
        releaseChannel = ClientPayload.UserAgent.ReleaseChannel.RELEASE,
        appVersion = ClientPayload.UserAgent.AppVersion(
            primary = WA_VERSION[0],
            secondary = WA_VERSION[1],
            tertiary = WA_VERSION[2],
        ),
        mcc = "000",
        mnc = "000",
        osVersion = "0.1",
        manufacturer = "",
        device = "Desktop",
        osBuildNumber = "0.1",
        localeLanguageIso6391 = "en",
        localeCountryIso31661Alpha2 = "US",
    )

    /** The companion device's self-description; `os` becomes the name shown in Linked Devices. */
    private fun deviceProps() = DeviceProps(
        os = "Kortex",
        version = DeviceProps.AppVersion(primary = 0, secondary = 1, tertiary = 0),
        platformType = DeviceProps.PlatformType.UNKNOWN,
        requireFullSync = false,
    )

    private fun versionHash(): ByteArray =
        MessageDigest.getInstance("MD5").digest(WA_VERSION.joinToString(".").toByteArray())

    private fun be32(value: Int): ByteArray = byteArrayOf(
        (value ushr 24).toByte(),
        (value ushr 16).toByte(),
        (value ushr 8).toByte(),
        value.toByte(),
    )
}
