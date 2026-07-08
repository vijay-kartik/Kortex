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
