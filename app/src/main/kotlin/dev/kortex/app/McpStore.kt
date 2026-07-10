package dev.kortex.app

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.mcpDataStore: DataStore<Preferences> by preferencesDataStore(name = "mcp_settings")

/** A user-added MCP server, persisted in DataStore as JSON. */
@Serializable
data class CustomMcpServer(
    val name: String,
    val url: String,
    val bearerToken: String? = null,
)

/**
 * DataStore-backed persistence for the MCP settings screen:
 *  - custom (user-added) MCP servers
 *  - set of disabled tool names (applies to builtins + all MCP tools alike)
 */
class McpStore(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true }

    private companion object {
        val KEY_CUSTOM_SERVERS = stringPreferencesKey("custom_mcp_servers")
        val KEY_DISABLED_TOOLS = stringSetPreferencesKey("disabled_tools")
        val KEY_ACTIVE_MODEL = stringPreferencesKey("active_model")
        val KEY_ACTIVE_PROVIDER = stringPreferencesKey("active_provider")
        val KEY_OLLAMA_URL = stringPreferencesKey("ollama_url")
        val KEY_OLLAMA_TOKEN = stringPreferencesKey("ollama_token")
        val KEY_COMPOSIO_API_KEY = stringPreferencesKey("composio_api_key")
        val KEY_COMPOSIO_USER_ID = stringPreferencesKey("composio_user_id")
        val KEY_OPENAI_API_KEY = stringPreferencesKey("openai_api_key")
        val KEY_GMAIL_ACCOUNT = stringPreferencesKey("gmail_account_email")
    }

    /** User-entered OpenAI key; overrides the build's local.properties key when set. */
    val openaiApiKey: Flow<String?> = context.mcpDataStore.data.map { prefs ->
        prefs[KEY_OPENAI_API_KEY]
    }

    suspend fun setOpenaiApiKey(key: String?) {
        context.mcpDataStore.edit { prefs ->
            if (key.isNullOrBlank()) prefs.remove(KEY_OPENAI_API_KEY) else prefs[KEY_OPENAI_API_KEY] = key
        }
    }

    val activeModel: Flow<String> = context.mcpDataStore.data.map { prefs ->
        prefs[KEY_ACTIVE_MODEL] ?: "gpt-4o"
    }

    suspend fun setActiveModel(model: String) {
        context.mcpDataStore.edit { prefs ->
            prefs[KEY_ACTIVE_MODEL] = model
        }
    }

    val activeProvider: Flow<String> = context.mcpDataStore.data.map { prefs ->
        prefs[KEY_ACTIVE_PROVIDER] ?: "openai"
    }

    suspend fun setActiveProvider(provider: String) {
        context.mcpDataStore.edit { prefs ->
            prefs[KEY_ACTIVE_PROVIDER] = provider
        }
    }

    val ollamaUrl: Flow<String> = context.mcpDataStore.data.map { prefs ->
        prefs[KEY_OLLAMA_URL] ?: "http://10.0.2.2:11434/v1"
    }

    suspend fun setOllamaUrl(url: String) {
        context.mcpDataStore.edit { prefs ->
            prefs[KEY_OLLAMA_URL] = url
        }
    }

    val ollamaToken: Flow<String?> = context.mcpDataStore.data.map { prefs ->
        prefs[KEY_OLLAMA_TOKEN]
    }

    suspend fun setOllamaToken(token: String?) {
        context.mcpDataStore.edit { prefs ->
            if (token.isNullOrBlank()) {
                prefs.remove(KEY_OLLAMA_TOKEN)
            } else {
                prefs[KEY_OLLAMA_TOKEN] = token
            }
        }
    }

    // ── Composio (Gmail via Tool Router session) ──────────────────────────

    val composioApiKey: Flow<String?> = context.mcpDataStore.data.map { prefs ->
        prefs[KEY_COMPOSIO_API_KEY]
    }

    suspend fun setComposioApiKey(key: String?) {
        context.mcpDataStore.edit { prefs ->
            if (key.isNullOrBlank()) prefs.remove(KEY_COMPOSIO_API_KEY) else prefs[KEY_COMPOSIO_API_KEY] = key
        }
    }

    /** Must match the user_id the Gmail toolkit was authorized under in Composio. */
    val composioUserId: Flow<String?> = context.mcpDataStore.data.map { prefs ->
        prefs[KEY_COMPOSIO_USER_ID]
    }

    suspend fun setComposioUserId(userId: String?) {
        context.mcpDataStore.edit { prefs ->
            if (userId.isNullOrBlank()) prefs.remove(KEY_COMPOSIO_USER_ID) else prefs[KEY_COMPOSIO_USER_ID] = userId
        }
    }

    // ── Gmail (direct REST API via OAuth2) ────────────────────────────────

    /** The Google account email whose OAuth token is used for the Gmail tool. */
    val gmailAccountEmail: Flow<String?> = context.mcpDataStore.data.map { prefs ->
        prefs[KEY_GMAIL_ACCOUNT]
    }

    suspend fun setGmailAccountEmail(email: String?) {
        context.mcpDataStore.edit { prefs ->
            if (email.isNullOrBlank()) prefs.remove(KEY_GMAIL_ACCOUNT) else prefs[KEY_GMAIL_ACCOUNT] = email
        }
    }

    // ── custom servers ──────────────────────────────────────────────────

    val customServers: Flow<List<CustomMcpServer>> = context.mcpDataStore.data.map { prefs ->
        prefs[KEY_CUSTOM_SERVERS]
            ?.let { runCatching { json.decodeFromString<List<CustomMcpServer>>(it) }.getOrNull() }
            ?: emptyList()
    }

    suspend fun addServer(server: CustomMcpServer) {
        context.mcpDataStore.edit { prefs ->
            val current = prefs[KEY_CUSTOM_SERVERS]
                ?.let { runCatching { json.decodeFromString<List<CustomMcpServer>>(it) }.getOrNull() }
                ?: emptyList()
            prefs[KEY_CUSTOM_SERVERS] = json.encodeToString(current + server)
        }
    }

    suspend fun removeServer(name: String) {
        context.mcpDataStore.edit { prefs ->
            val current = prefs[KEY_CUSTOM_SERVERS]
                ?.let { runCatching { json.decodeFromString<List<CustomMcpServer>>(it) }.getOrNull() }
                ?: emptyList()
            prefs[KEY_CUSTOM_SERVERS] = json.encodeToString(current.filter { it.name != name })
        }
    }

    // ── disabled tools ──────────────────────────────────────────────────

    val disabledTools: Flow<Set<String>> = context.mcpDataStore.data.map { prefs ->
        prefs[KEY_DISABLED_TOOLS] ?: emptySet()
    }

    suspend fun setToolDisabled(toolName: String, disabled: Boolean) {
        context.mcpDataStore.edit { prefs ->
            val current = prefs[KEY_DISABLED_TOOLS]?.toMutableSet() ?: mutableSetOf()
            if (disabled) current += toolName else current -= toolName
            prefs[KEY_DISABLED_TOOLS] = current
        }
    }
}
