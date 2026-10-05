package dev.kortex.finance.data.secure

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.google.crypto.tink.subtle.AesGcmJce
import dev.kortex.finance.domain.port.DataKey
import dev.kortex.finance.domain.port.FinanceKeySource
import dev.kortex.finance.domain.port.SealedSecret
import dev.kortex.finance.domain.port.SecretBox
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Full numbers sealed with Tink's AES-256-GCM under the user's data key, bound to their account's
 * uid as associated data. The data key is fetched once per user and cached on the phone wrapped by
 * an Android Keystore key, which never leaves the phone's secure hardware.
 */
class KeystoreSecretBox(
    context: Context,
    private val keys: FinanceKeySource,
) : SecretBox {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val mutex = Mutex()
    private var cached: DataKey? = null

    override suspend fun seal(accountUid: String, plain: String): SealedSecret? = withContext(Dispatchers.IO) {
        val key = dataKey() ?: return@withContext null
        runCatching {
            val sealed = AesGcmJce(key.bytes).encrypt(plain.toByteArray(Charsets.UTF_8), associatedData(accountUid))
            SealedSecret(Base64.encodeToString(sealed, Base64.NO_WRAP), key.version)
        }.getOrNull()
    }

    override suspend fun open(accountUid: String, sealed: SealedSecret): String? = withContext(Dispatchers.IO) {
        val key = dataKey()?.takeIf { it.version == sealed.keyVersion } ?: return@withContext null
        runCatching {
            String(AesGcmJce(key.bytes).decrypt(Base64.decode(sealed.cipherText, Base64.NO_WRAP), associatedData(accountUid)), Charsets.UTF_8)
        }.getOrNull()
    }

    /** The signed-in user's key: from memory, else the wrapped copy on the phone, else the server. */
    private suspend fun dataKey(): DataKey? = mutex.withLock {
        val user = keys.currentUserUid() ?: return null
        cached?.takeIf { it.userUid == user }?.let { return it }
        val stored = prefs.getString(KEY_PREFIX + user, null)?.let { unwrap(it) }
        val key = if (stored != null) {
            DataKey(user, stored, prefs.getInt(VERSION_PREFIX + user, 1))
        } else {
            val fetched = keys.fetch()?.takeIf { it.userUid == user } ?: return null
            prefs.edit().putString(KEY_PREFIX + user, wrap(fetched.bytes)).putInt(VERSION_PREFIX + user, fetched.version).apply()
            fetched
        }
        cached = key
        key
    }

    private fun associatedData(accountUid: String) = "kortex.finance.secret:$accountUid".toByteArray(Charsets.UTF_8)

    private fun wrap(bytes: ByteArray): String {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, wrappingKey()) }
        return Base64.encodeToString(cipher.iv + cipher.doFinal(bytes), Base64.NO_WRAP)
    }

    private fun unwrap(text: String): ByteArray? = runCatching {
        val data = Base64.decode(text, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, wrappingKey(), GCMParameterSpec(128, data, 0, IV_BYTES))
        }
        cipher.doFinal(data, IV_BYTES, data.size - IV_BYTES)
    }.getOrNull()

    private fun wrappingKey(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getKey(WRAP_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).apply {
            init(
                KeyGenParameterSpec.Builder(WRAP_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
        }.generateKey()
    }

    private companion object {
        const val PREFS = "finance_keys"
        const val KEY_PREFIX = "data_key:"
        const val VERSION_PREFIX = "data_key_version:"
        const val KEYSTORE = "AndroidKeyStore"
        const val WRAP_ALIAS = "kortex.finance.data_key_wrap"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
    }
}
