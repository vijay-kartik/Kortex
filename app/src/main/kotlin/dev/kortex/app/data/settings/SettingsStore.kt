package dev.kortex.app.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import dev.kortex.app.domain.agent.ModelSelection
import dev.kortex.core.llm.LlmProviderSettings
import dev.kortex.core.mcp.PendingAuthorization

private val Context.mcpDataStore: DataStore<Preferences> by preferencesDataStore(name = "mcp_settings")

/** A user-added MCP server, persisted in DataStore as JSON. */
@Serializable
data class CustomMcpServer(
    val name: String,
    val url: String,
    val bearerToken: String? = null,
)

@Serializable
data class McpOAuthState(
    val clientId: String,
    val accessToken: String,
    val refreshToken: String? = null,
    val expiresAtMillis: Long? = null,
    val tokenEndpoint: String
)

/**
 * DataStore-backed persistence for the MCP settings screen:
 *  - custom (user-added) MCP servers
 *  - set of disabled tool names (applies to builtins + all MCP tools alike)
 */
class SettingsStore(
    private val store: DataStore<Preferences>,
    private val cipher: SecretCipher,
) : ModelSelection {

    constructor(context: Context) : this(context.mcpDataStore, KeystoreSecretCipher())

    private val json = Json { ignoreUnknownKeys = true }

    @Volatile private var migrated = false

    /** Every read goes through here so plaintext secrets from older versions are re-encrypted first. */
    private val data: Flow<Preferences> = store.data.onStart { migrateSecrets() }

    private fun seal(plain: String): String = SEALED_PREFIX + cipher.encrypt(plain)

    /** Reads a stored secret; a value without the prefix is a legacy plaintext one, kept readable until migrated. */
    private fun open(stored: String?): String? = when {
        stored == null -> null
        stored.startsWith(SEALED_PREFIX) -> cipher.decrypt(stored.removePrefix(SEALED_PREFIX))
        else -> stored
    }

    private fun McpOAuthState.sealed() = copy(
        accessToken = seal(accessToken),
        refreshToken = refreshToken?.let(::seal),
    )

    private fun McpOAuthState.opened(): McpOAuthState? {
        val access = open(accessToken) ?: return null
        return copy(accessToken = access, refreshToken = refreshToken?.let { open(it) })
    }

    private fun decodeOauth(prefs: Preferences): Map<String, McpOAuthState> =
        prefs[KEY_OAUTH_STATES]
            ?.let { runCatching { json.decodeFromString<Map<String, McpOAuthState>>(it) }.getOrNull() }
            ?: emptyMap()

    /**
     * One-time, idempotent: rewrites any plaintext API key / token / OAuth token in place as cipher
     * text. If sealing fails the plaintext is left untouched, so nothing is lost and it retries next time.
     */
    internal suspend fun migrateSecrets() {
        if (migrated) return
        store.edit { prefs ->
            for (key in SECRET_KEYS) {
                val value = prefs[key] ?: continue
                if (value.startsWith(SEALED_PREFIX)) continue
                runCatching { seal(value) }.getOrNull()?.let { prefs[key] = it }
            }
            val states = decodeOauth(prefs)
            if (states.values.any { !it.accessToken.startsWith(SEALED_PREFIX) || it.refreshToken?.startsWith(SEALED_PREFIX) == false }) {
                val updated = runCatching {
                    states.mapValues { (_, s) ->
                        s.copy(
                            accessToken = if (s.accessToken.startsWith(SEALED_PREFIX)) s.accessToken else seal(s.accessToken),
                            refreshToken = s.refreshToken?.let { if (it.startsWith(SEALED_PREFIX)) it else seal(it) },
                        )
                    }
                }.getOrNull()
                if (updated != null) prefs[KEY_OAUTH_STATES] = json.encodeToString(updated)
            }
        }
        migrated = true
    }

    private companion object {
        val KEY_CUSTOM_SERVERS = stringPreferencesKey("custom_mcp_servers")
        val KEY_DISABLED_TOOLS = stringSetPreferencesKey("disabled_tools")
        val KEY_DEFAULT_DISABLED_SEEN = stringSetPreferencesKey("default_disabled_seen_tools")
        val KEY_ACTIVE_MODEL = stringPreferencesKey("active_model")
        val KEY_ACTIVE_PROVIDER = stringPreferencesKey("active_provider")
        val KEY_OLLAMA_URL = stringPreferencesKey("ollama_url")
        val KEY_OLLAMA_TOKEN = stringPreferencesKey("ollama_token")
        val KEY_OLLAMA_CLOUD_API_KEY = stringPreferencesKey("ollama_cloud_api_key")
        val KEY_OPENAI_API_KEY = stringPreferencesKey("openai_api_key")
        val KEY_GMAIL_ACCOUNT = stringPreferencesKey("gmail_account_email")
        val KEY_OAUTH_STATES = stringPreferencesKey("mcp_oauth_states")
        val KEY_PENDING_AUTH = stringPreferencesKey("mcp_pending_auth")
        const val SEALED_PREFIX = "enc:v1:"
        val SECRET_KEYS = listOf(KEY_OPENAI_API_KEY, KEY_OLLAMA_TOKEN, KEY_OLLAMA_CLOUD_API_KEY)
    }

    /** User-entered OpenAI key; overrides the build's local.properties key when set. */
    val openaiApiKey: Flow<String?> = data.map { prefs ->
        open(prefs[KEY_OPENAI_API_KEY])
    }

    suspend fun setOpenaiApiKey(key: String?) {
        store.edit { prefs ->
            if (key.isNullOrBlank()) prefs.remove(KEY_OPENAI_API_KEY) else prefs[KEY_OPENAI_API_KEY] = seal(key)
        }
    }

    override val activeModel: Flow<String> = data.map { prefs ->
        prefs[KEY_ACTIVE_MODEL] ?: "gemma4:31b"
    }

    suspend fun setActiveModel(model: String) {
        store.edit { prefs ->
            prefs[KEY_ACTIVE_MODEL] = model
        }
    }

    override val activeProvider: Flow<String> = data.map { prefs ->
        prefs[KEY_ACTIVE_PROVIDER] ?: "ollama-cloud"
    }

    suspend fun setActiveProvider(provider: String) {
        store.edit { prefs ->
            prefs[KEY_ACTIVE_PROVIDER] = provider
        }
    }

    val ollamaUrl: Flow<String> = data.map { prefs ->
        prefs[KEY_OLLAMA_URL] ?: "http://10.0.2.2:11434/v1"
    }

    suspend fun setOllamaUrl(url: String) {
        store.edit { prefs ->
            prefs[KEY_OLLAMA_URL] = url
        }
    }

    val ollamaToken: Flow<String?> = data.map { prefs ->
        open(prefs[KEY_OLLAMA_TOKEN])
    }

    suspend fun setOllamaToken(token: String?) {
        store.edit { prefs ->
            if (token.isNullOrBlank()) {
                prefs.remove(KEY_OLLAMA_TOKEN)
            } else {
                prefs[KEY_OLLAMA_TOKEN] = seal(token)
            }
        }
    }

    /** API key for Ollama Cloud (ollama.com) — created at ollama.com/settings/keys. */
    val ollamaCloudApiKey: Flow<String?> = data.map { prefs ->
        open(prefs[KEY_OLLAMA_CLOUD_API_KEY])
    }

    suspend fun setOllamaCloudApiKey(key: String?) {
        store.edit { prefs ->
            if (key.isNullOrBlank()) prefs.remove(KEY_OLLAMA_CLOUD_API_KEY) else prefs[KEY_OLLAMA_CLOUD_API_KEY] = seal(key)
        }
    }

    // ── Gmail (direct REST API via OAuth2) ────────────────────────────────

    /** The Google account email whose OAuth token is used for the Gmail tool. */
    val gmailAccountEmail: Flow<String?> = data.map { prefs ->
        prefs[KEY_GMAIL_ACCOUNT]
    }

    suspend fun setGmailAccountEmail(email: String?) {
        store.edit { prefs ->
            if (email.isNullOrBlank()) prefs.remove(KEY_GMAIL_ACCOUNT) else prefs[KEY_GMAIL_ACCOUNT] = email
        }
    }

    // ── custom servers ──────────────────────────────────────────────────

    val customServers: Flow<List<CustomMcpServer>> = data.map { prefs ->
        prefs[KEY_CUSTOM_SERVERS]
            ?.let { runCatching { json.decodeFromString<List<CustomMcpServer>>(it) }.getOrNull() }
            ?: emptyList()
    }

    suspend fun addServer(server: CustomMcpServer) {
        store.edit { prefs ->
            val current = prefs[KEY_CUSTOM_SERVERS]
                ?.let { runCatching { json.decodeFromString<List<CustomMcpServer>>(it) }.getOrNull() }
                ?: emptyList()
            prefs[KEY_CUSTOM_SERVERS] = json.encodeToString(current + server)
        }
    }

    suspend fun removeServer(name: String) {
        store.edit { prefs ->
            val current = prefs[KEY_CUSTOM_SERVERS]
                ?.let { runCatching { json.decodeFromString<List<CustomMcpServer>>(it) }.getOrNull() }
                ?: emptyList()
            prefs[KEY_CUSTOM_SERVERS] = json.encodeToString(current.filter { it.name != name })
        }
    }

    // ── disabled tools ──────────────────────────────────────────────────

    val disabledTools: Flow<Set<String>> = data.map { prefs ->
        prefs[KEY_DISABLED_TOOLS] ?: emptySet()
    }

    suspend fun setToolDisabled(toolName: String, disabled: Boolean) {
        store.edit { prefs ->
            val set = prefs[KEY_DISABLED_TOOLS] ?: emptySet()
            if (disabled) {
                prefs[KEY_DISABLED_TOOLS] = set + toolName
            } else {
                prefs[KEY_DISABLED_TOOLS] = set - toolName
            }
        }
    }

    suspend fun setServerToolsDisabled(toolNames: List<String>, disabled: Boolean) {
        store.edit { prefs ->
            val set = prefs[KEY_DISABLED_TOOLS] ?: emptySet()
            if (disabled) {
                prefs[KEY_DISABLED_TOOLS] = set + toolNames
            } else {
                prefs[KEY_DISABLED_TOOLS] = set - toolNames.toSet()
            }
        }
    }

    /**
     * Disables every tool in [names] that has never been seen before, and remembers it as
     * seen. Used for tools that must be opt-in (Composio Gmail): the first time a name shows
     * up it lands in the disabled set, but a user's later enable/disable toggle is never
     * overridden on reconnect because the name is already marked seen.
     */
    suspend fun defaultDisableNewTools(names: Collection<String>) {
        if (names.isEmpty()) return
        store.edit { prefs ->
            val seen = prefs[KEY_DEFAULT_DISABLED_SEEN] ?: emptySet()
            val new = names.filterNot { it in seen }
            if (new.isEmpty()) return@edit
            prefs[KEY_DISABLED_TOOLS] = (prefs[KEY_DISABLED_TOOLS] ?: emptySet()) + new
            prefs[KEY_DEFAULT_DISABLED_SEEN] = seen + new
        }
    }

    // ── OAuth states ────────────────────────────────────────────────────

    // accessToken / refreshToken are stored as cipher text (see [SecretCipher]); the rest is plain JSON.

    val oauthStates: Flow<Map<String, McpOAuthState>> = data.map { prefs ->
        decodeOauth(prefs).mapNotNull { (url, s) -> s.opened()?.let { url to it } }.toMap()
    }

    suspend fun setOauthState(url: String, state: McpOAuthState?) {
        store.edit { prefs ->
            val current = decodeOauth(prefs)

            val updated = if (state == null) current - url else current + (url to state.sealed())
            if (updated.isEmpty()) {
                prefs.remove(KEY_OAUTH_STATES)
            } else {
                prefs[KEY_OAUTH_STATES] = json.encodeToString(updated)
            }
        }
    }

    /** Consume-once read-and-clear accessor for the in-flight PendingAuthorization. */
    suspend fun consumePendingAuth(): PendingAuthorization? {
        var auth: PendingAuthorization? = null
        store.edit { prefs ->
            val jsonStr = prefs[KEY_PENDING_AUTH]
            if (jsonStr != null) {
                auth = runCatching { json.decodeFromString<PendingAuthorization>(jsonStr) }.getOrNull()
                prefs.remove(KEY_PENDING_AUTH)
            }
        }
        return auth
    }

    suspend fun setPendingAuth(auth: PendingAuthorization?) {
        store.edit { prefs ->
            if (auth == null) {
                prefs.remove(KEY_PENDING_AUTH)
            } else {
                prefs[KEY_PENDING_AUTH] = json.encodeToString(auth)
            }
        }
    }
}

/** Exposes the provider choice and keys stored here to core-agent's DynamicLlmProvider. */
fun SettingsStore.asLlmProviderSettings(): LlmProviderSettings = object : LlmProviderSettings {
    override suspend fun activeProvider() = this@asLlmProviderSettings.activeProvider.first()
    override suspend fun openAiApiKey() = openaiApiKey.first()
    override suspend fun ollamaUrl() = this@asLlmProviderSettings.ollamaUrl.first()
    override suspend fun ollamaToken() = this@asLlmProviderSettings.ollamaToken.first()
    override suspend fun ollamaCloudApiKey() = this@asLlmProviderSettings.ollamaCloudApiKey.first()
}
