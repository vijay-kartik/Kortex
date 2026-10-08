package dev.kortex.app.data.settings

import dev.kortex.app.data.auth.McpOAuthManager
import dev.kortex.app.domain.agent.McpServerRepository
import dev.kortex.core.mcp.McpServer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** User-added MCP servers from [SettingsStore], each wired to its OAuth session in [McpOAuthManager]. */
class McpServerStore(
    private val store: SettingsStore,
    private val oauth: McpOAuthManager,
) : McpServerRepository {

    override val customServers: Flow<List<McpServer>> = store.customServers.map { servers ->
        servers.map {
            McpServer(
                name = it.name,
                url = it.url,
                bearerToken = it.bearerToken,
                tokenProvider = oauth.tokenProviderFor(it.url),
            )
        }
    }

    override val sessionTokens: Flow<Map<String, String>> =
        store.oauthStates.map { states -> states.mapValues { it.value.accessToken } }

    override suspend fun addServer(name: String, url: String, bearerToken: String?) =
        store.addServer(CustomMcpServer(name = name, url = url, bearerToken = bearerToken))

    override suspend fun removeServer(name: String) = store.removeServer(name)

    override suspend fun clearSession(url: String) = store.setOauthState(url, null)

    override suspend fun beginSignIn(server: McpServer) = oauth.beginSignIn(server)
}
