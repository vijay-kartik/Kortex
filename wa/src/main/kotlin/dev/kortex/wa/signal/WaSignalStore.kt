package dev.kortex.wa.signal

import dev.kortex.wa.auth.DeviceCredentials
import dev.kortex.wa.store.KeyValueStore
import org.whispersystems.libsignal.IdentityKey
import org.whispersystems.libsignal.IdentityKeyPair
import org.whispersystems.libsignal.InvalidKeyIdException
import org.whispersystems.libsignal.SignalProtocolAddress
import org.whispersystems.libsignal.groups.SenderKeyName
import org.whispersystems.libsignal.groups.state.SenderKeyRecord
import org.whispersystems.libsignal.groups.state.SenderKeyStore
import org.whispersystems.libsignal.state.IdentityKeyStore
import org.whispersystems.libsignal.state.PreKeyRecord
import org.whispersystems.libsignal.state.SessionRecord
import org.whispersystems.libsignal.state.SignalProtocolStore
import org.whispersystems.libsignal.state.SignedPreKeyRecord

/**
 * libsignal [SignalProtocolStore] (+ [SenderKeyStore]) backed by the [KeyValueStore] SPI.
 * Records are persisted as their libsignal serialized bytes, keyed by id/address. Our own
 * identity key pair, registration id and signed pre-key come from [DeviceCredentials].
 *
 * Identity trust is trust-on-first-use (whatsmeow/Baileys do the same): WhatsApp's device list
 * is authenticated separately, so we accept identities here.
 *
 * All methods are blocking (libsignal's contract) — invoke on a background dispatcher.
 */
class WaSignalStore(
    private val credentials: DeviceCredentials,
    private val kv: KeyValueStore,
) : SignalProtocolStore, SenderKeyStore {

    // --- IdentityKeyStore ---

    override fun getIdentityKeyPair(): IdentityKeyPair = SignalKeys.identityKeyPair(credentials)

    override fun getLocalRegistrationId(): Int = credentials.registrationId

    override fun saveIdentity(address: SignalProtocolAddress, identityKey: IdentityKey): Boolean {
        val key = address.toKey()
        val existing = kv.get(KeyValueStore.NS_IDENTITY, key)
        val serialized = identityKey.serialize()
        kv.put(KeyValueStore.NS_IDENTITY, key, serialized)
        return existing != null && !existing.contentEquals(serialized)
    }

    override fun isTrustedIdentity(
        address: SignalProtocolAddress,
        identityKey: IdentityKey,
        direction: IdentityKeyStore.Direction,
    ): Boolean = true

    override fun getIdentity(address: SignalProtocolAddress): IdentityKey? =
        kv.get(KeyValueStore.NS_IDENTITY, address.toKey())?.let { IdentityKey(it, 0) }

    // --- PreKeyStore ---

    override fun loadPreKey(preKeyId: Int): PreKeyRecord =
        kv.get(KeyValueStore.NS_PREKEY, preKeyId.toString())?.let { PreKeyRecord(it) }
            ?: throw InvalidKeyIdException("no pre-key $preKeyId")

    override fun storePreKey(preKeyId: Int, record: PreKeyRecord) =
        kv.put(KeyValueStore.NS_PREKEY, preKeyId.toString(), record.serialize())

    override fun containsPreKey(preKeyId: Int): Boolean =
        kv.get(KeyValueStore.NS_PREKEY, preKeyId.toString()) != null

    override fun removePreKey(preKeyId: Int) =
        kv.delete(KeyValueStore.NS_PREKEY, preKeyId.toString())

    // --- SignedPreKeyStore (falls back to our generated signed pre-key) ---

    override fun loadSignedPreKey(signedPreKeyId: Int): SignedPreKeyRecord {
        kv.get(KeyValueStore.NS_SIGNED_PREKEY, signedPreKeyId.toString())?.let { return SignedPreKeyRecord(it) }
        if (signedPreKeyId == credentials.signedPreKey.keyId) return SignalKeys.signedPreKeyRecord(credentials)
        throw InvalidKeyIdException("no signed pre-key $signedPreKeyId")
    }

    override fun loadSignedPreKeys(): List<SignedPreKeyRecord> {
        val stored = kv.values(KeyValueStore.NS_SIGNED_PREKEY).map { SignedPreKeyRecord(it) }.toMutableList()
        if (stored.none { it.id == credentials.signedPreKey.keyId }) {
            stored += SignalKeys.signedPreKeyRecord(credentials)
        }
        return stored
    }

    override fun storeSignedPreKey(signedPreKeyId: Int, record: SignedPreKeyRecord) =
        kv.put(KeyValueStore.NS_SIGNED_PREKEY, signedPreKeyId.toString(), record.serialize())

    override fun containsSignedPreKey(signedPreKeyId: Int): Boolean =
        kv.get(KeyValueStore.NS_SIGNED_PREKEY, signedPreKeyId.toString()) != null ||
            signedPreKeyId == credentials.signedPreKey.keyId

    override fun removeSignedPreKey(signedPreKeyId: Int) =
        kv.delete(KeyValueStore.NS_SIGNED_PREKEY, signedPreKeyId.toString())

    // --- SessionStore ---

    override fun loadSession(address: SignalProtocolAddress): SessionRecord =
        kv.get(KeyValueStore.NS_SESSION, address.toKey())?.let { SessionRecord(it) } ?: SessionRecord()

    override fun getSubDeviceSessions(name: String): List<Int> =
        kv.keys(KeyValueStore.NS_SESSION)
            .filter { it.startsWith("$name.") }
            .mapNotNull { it.substringAfterLast('.').toIntOrNull() }
            .filter { it != 0 }

    override fun storeSession(address: SignalProtocolAddress, record: SessionRecord) =
        kv.put(KeyValueStore.NS_SESSION, address.toKey(), record.serialize())

    override fun containsSession(address: SignalProtocolAddress): Boolean =
        kv.get(KeyValueStore.NS_SESSION, address.toKey()) != null

    override fun deleteSession(address: SignalProtocolAddress) =
        kv.delete(KeyValueStore.NS_SESSION, address.toKey())

    override fun deleteAllSessions(name: String) {
        kv.keys(KeyValueStore.NS_SESSION).filter { it.startsWith("$name.") }
            .forEach { kv.delete(KeyValueStore.NS_SESSION, it) }
    }

    // --- SenderKeyStore (groups) ---

    override fun storeSenderKey(senderKeyName: SenderKeyName, record: SenderKeyRecord) =
        kv.put(KeyValueStore.NS_SENDER_KEY, senderKeyName.toKey(), record.serialize())

    override fun loadSenderKey(senderKeyName: SenderKeyName): SenderKeyRecord =
        kv.get(KeyValueStore.NS_SENDER_KEY, senderKeyName.toKey())?.let { SenderKeyRecord(it) } ?: SenderKeyRecord()

    private fun SignalProtocolAddress.toKey(): String = "$name.$deviceId"
    private fun SenderKeyName.toKey(): String = "$groupId::${sender.name}.${sender.deviceId}"
}
