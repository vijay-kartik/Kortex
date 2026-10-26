package dev.kortex.app.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SettingsStoreSecretsTest {

    @get:Rule val tmp = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var store: SettingsStore

    /** Reversible stand-in for the Keystore: reversed text, so it is visibly not the plaintext. */
    private val cipher = object : SecretCipher {
        override fun encrypt(plain: String) = plain.reversed() + "#"
        override fun decrypt(sealed: String) = if (sealed.endsWith("#")) sealed.dropLast(1).reversed() else null
    }

    private val openaiKey = stringPreferencesKey("openai_api_key")
    private val ollamaToken = stringPreferencesKey("ollama_token")
    private val oauthKey = stringPreferencesKey("mcp_oauth_states")

    @Before fun setUp() {
        dataStore = PreferenceDataStoreFactory.create(scope = scope) { File(tmp.root, "mcp_settings.preferences_pb") }
        store = SettingsStore(dataStore, cipher)
    }

    @After fun tearDown() = scope.cancel()

    @Test fun `api keys round trip but are not stored in clear text`() = runTest {
        store.setOpenaiApiKey("sk-secret")
        store.setOllamaToken("tok-secret")
        store.setOllamaCloudApiKey("cloud-secret")

        assertEquals("sk-secret", store.openaiApiKey.first())
        assertEquals("tok-secret", store.ollamaToken.first())
        assertEquals("cloud-secret", store.ollamaCloudApiKey.first())
        val raw = dataStore.data.first().asMap().values.joinToString()
        assertFalse(raw.contains("sk-secret"))
        assertFalse(raw.contains("tok-secret"))
        assertFalse(raw.contains("cloud-secret"))
    }

    @Test fun `oauth tokens round trip but are not stored in clear text`() = runTest {
        val state = McpOAuthState("client", "access-secret", "refresh-secret", 123L, "https://t/token")
        store.setOauthState("https://mcp", state)

        assertEquals(mapOf("https://mcp" to state), store.oauthStates.first())
        val raw = dataStore.data.first()[oauthKey]!!
        assertFalse(raw.contains("access-secret"))
        assertFalse(raw.contains("refresh-secret"))
        assertTrue(raw.contains("client"))
    }

    @Test fun `clearing a key removes it`() = runTest {
        store.setOpenaiApiKey("sk-secret")
        store.setOpenaiApiKey(null)
        assertNull(store.openaiApiKey.first())
    }

    @Test fun `plaintext values from older versions are re-encrypted on first read`() = runTest {
        dataStore.edit {
            it[openaiKey] = "sk-old"
            it[ollamaToken] = "tok-old"
            it[oauthKey] = """{"https://mcp":{"clientId":"c","accessToken":"acc-old","refreshToken":"ref-old","tokenEndpoint":"https://t"}}"""
        }

        assertEquals("sk-old", store.openaiApiKey.first())
        assertEquals("tok-old", store.ollamaToken.first())
        val oauth = store.oauthStates.first().getValue("https://mcp")
        assertEquals("acc-old", oauth.accessToken)
        assertEquals("ref-old", oauth.refreshToken)

        val prefs = dataStore.data.first()
        assertFalse(prefs[openaiKey]!!.contains("sk-old"))
        assertFalse(prefs[ollamaToken]!!.contains("tok-old"))
        assertFalse(prefs[oauthKey]!!.contains("acc-old"))
        assertFalse(prefs[oauthKey]!!.contains("ref-old"))
    }

    @Test fun `migration does not encrypt twice`() = runTest {
        store.setOpenaiApiKey("sk-secret")
        val before = dataStore.data.first()[openaiKey]

        store.migrateSecrets()

        assertEquals(before, dataStore.data.first()[openaiKey])
        assertEquals("sk-secret", store.openaiApiKey.first())
    }

    @Test fun `a value that cannot be decrypted reads as missing`() = runTest {
        dataStore.edit { it[openaiKey] = "enc:v1:garbage" }
        assertNull(store.openaiApiKey.first())
    }
}
