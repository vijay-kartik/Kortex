package dev.kortex.app

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Upsert
import dev.kortex.wa.auth.CredentialStore
import dev.kortex.wa.auth.DeviceCredentials
import dev.kortex.wa.auth.SignedPreKey
import dev.kortex.wa.crypto.KeyPair25519
import dev.kortex.wa.store.KeyValueStore

// --- Entities ---

@Entity(tableName = "wa_kv", primaryKeys = ["namespace", "name"])
data class WaKvEntity(val namespace: String, val name: String, val value: ByteArray)

@Entity(tableName = "wa_credentials")
data class WaCredentialsEntity(
    @PrimaryKey val id: Int = 0,
    val noisePriv: ByteArray, val noisePub: ByteArray,
    val identityPriv: ByteArray, val identityPub: ByteArray,
    val spkKeyId: Int, val spkPriv: ByteArray, val spkPub: ByteArray, val spkSig: ByteArray,
    val registrationId: Int, val advSecret: ByteArray,
    val deviceJid: String?, val accountSignedDeviceIdentity: ByteArray?, val pushName: String?,
)

// --- DAOs ---

@Dao
interface WaKvDao {
    @Query("SELECT value FROM wa_kv WHERE namespace = :ns AND name = :name")
    fun get(ns: String, name: String): ByteArray?

    @Upsert
    fun put(entity: WaKvEntity)

    @Query("DELETE FROM wa_kv WHERE namespace = :ns AND name = :name")
    fun delete(ns: String, name: String)

    @Query("SELECT name FROM wa_kv WHERE namespace = :ns")
    fun keys(ns: String): List<String>

    @Query("SELECT value FROM wa_kv WHERE namespace = :ns")
    fun values(ns: String): List<ByteArray>
}

@Dao
interface WaCredentialsDao {
    @Query("SELECT * FROM wa_credentials WHERE id = 0")
    suspend fun get(): WaCredentialsEntity?

    @Upsert
    suspend fun upsert(entity: WaCredentialsEntity)

    @Query("DELETE FROM wa_credentials")
    suspend fun clear()
}

@Database(entities = [WaKvEntity::class, WaCredentialsEntity::class], version = 1, exportSchema = false)
abstract class WaDatabase : RoomDatabase() {
    abstract fun kvDao(): WaKvDao
    abstract fun credentialsDao(): WaCredentialsDao
}

// --- Store implementations (the SPIs that :wa depends on) ---

class RoomKeyValueStore(private val dao: WaKvDao) : KeyValueStore {
    override fun get(namespace: String, key: String): ByteArray? = dao.get(namespace, key)
    override fun put(namespace: String, key: String, value: ByteArray) = dao.put(WaKvEntity(namespace, key, value))
    override fun delete(namespace: String, key: String) = dao.delete(namespace, key)
    override fun keys(namespace: String): List<String> = dao.keys(namespace)
    override fun values(namespace: String): List<ByteArray> = dao.values(namespace)
}

class RoomCredentialStore(private val dao: WaCredentialsDao) : CredentialStore {
    override suspend fun load(): DeviceCredentials? = dao.get()?.toDomain()
    override suspend fun save(credentials: DeviceCredentials) = dao.upsert(credentials.toEntity())
    override suspend fun clear() = dao.clear()
}

private fun DeviceCredentials.toEntity() = WaCredentialsEntity(
    noisePriv = noiseKey.privateKey, noisePub = noiseKey.publicKey,
    identityPriv = identityKey.privateKey, identityPub = identityKey.publicKey,
    spkKeyId = signedPreKey.keyId, spkPriv = signedPreKey.keyPair.privateKey,
    spkPub = signedPreKey.keyPair.publicKey, spkSig = signedPreKey.signature,
    registrationId = registrationId, advSecret = advSecretKey,
    deviceJid = deviceJid, accountSignedDeviceIdentity = accountSignedDeviceIdentity, pushName = pushName,
)

private fun WaCredentialsEntity.toDomain() = DeviceCredentials(
    noiseKey = KeyPair25519(noisePriv, noisePub),
    identityKey = KeyPair25519(identityPriv, identityPub),
    signedPreKey = SignedPreKey(spkKeyId, KeyPair25519(spkPriv, spkPub), spkSig),
    registrationId = registrationId,
    advSecretKey = advSecret,
    deviceJid = deviceJid,
    accountSignedDeviceIdentity = accountSignedDeviceIdentity,
    pushName = pushName,
)
