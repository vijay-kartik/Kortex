package dev.kortex.app.domain.agent

import dev.kortex.core.log.Logger
import dev.kortex.core.log.w
import dev.kortex.core.mcp.McpServer
import dev.kortex.core.mcp.McpUnauthorizedException
import dev.kortex.core.mcp.mcpServers
import dev.kortex.core.tool.Tool
import dev.kortex.core.tool.ToolRegistry
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

enum class ServerStatus { CONNECTING, CONNECTED, ERROR, NEEDS_AUTH }

/** An MCP server as Settings lists it: a hardcoded default or one the user added. */
data class McpServerInfo(val name: String, val url: String, val isDefault: Boolean)

/** Where user-added MCP servers and their OAuth sessions live. */
interface McpServerRepository {
    /** User-added servers, carrying the bearer token or OAuth token provider they connect with. */
    val customServers: Flow<List<McpServer>>

    /** OAuth access token per server URL; a new or changed token means a sign-in or refresh. */
    val sessionTokens: Flow<Map<String, String>>

    suspend fun addServer(name: String, url: String, bearerToken: String?)
    suspend fun removeServer(name: String)
    suspend fun clearSession(url: String)
    suspend fun beginSignIn(server: McpServer)
}

/**
 * The one owner of MCP connections: connects the default and user-added servers into the
 * shared [ToolRegistry], tracks each server's status and the tools it registered, and removes
 * those tools again on sign-out or delete. AgentBootstrap starts it; Settings shows its state.
 *
 * Call it from the main thread: [ToolRegistry] isn't thread-safe.
 */
class McpConnections(
    private val tools: ToolRegistry,
    private val repository: McpServerRepository,
    /** Registers a server's tools and returns their names; see `McpToolConnector.connect`. */
    private val connectServer: suspend (McpServer) -> List<String>,
    private val defaults: List<McpServer> = mcpServers,
    private val logger: Logger = Logger.CONSOLE,
) {
    private val _statuses = MutableStateFlow<Map<String, ServerStatus>>(emptyMap())

    /** Per-server status, keyed by server name; servers not connected yet have no entry. */
    val statuses: StateFlow<Map<String, ServerStatus>> = _statuses.asStateFlow()

    private val _toolsByServer = MutableStateFlow<Map<String, List<Tool>>>(emptyMap())

    /** The tools each server registered, keyed by server name. */
    val toolsByServer: StateFlow<Map<String, List<Tool>>> = _toolsByServer.asStateFlow()

    /** Every known server, defaults first. */
    val servers: Flow<List<McpServerInfo>> = repository.customServers.map { custom ->
        defaults.map { McpServerInfo(it.name, it.url, isDefault = true) } +
            custom.map { McpServerInfo(it.name, it.url, isDefault = false) }
    }

    /** URLs of servers holding an OAuth session. */
    val signedInUrls: Flow<Set<String>> = repository.sessionTokens.map { it.keys }

    /** Registered tools that no MCP server owns, i.e. the app's own tools. */
    fun builtinTools(): List<Tool> {
        val mcpOwned = _toolsByServer.value.values.flatten().mapTo(mutableSetOf()) { it.name }
        return tools.allIncludingDisabled().filter { it.name !in mcpOwned }
    }

    /** Connects every known server; run once at startup. */
    suspend fun connectAll() {
        for (server in allServers()) connect(server)
    }

    /** Reconnects a server whenever it gets a new OAuth token (e.g. after the sign-in callback). Never returns. */
    suspend fun reconnectOnSignIn() {
        var previous: Map<String, String>? = null
        repository.sessionTokens.collect { current ->
            val before = previous
            previous = current
            if (before == null) return@collect
            val changedUrls = current.filter { (url, token) -> before[url] != token }.keys
            for (server in allServers().filter { it.url in changedUrls }) connect(server)
        }
    }

    suspend fun add(name: String, url: String, bearerToken: String?) {
        repository.addServer(name, url, bearerToken)
        repository.customServers.first().find { it.name == name }?.let { connect(it) }
    }

    suspend fun remove(name: String) {
        dropTools(name)
        _statuses.update { it - name }
        repository.removeServer(name)
    }

    /** Only user-added servers can sign in with OAuth. */
    suspend fun signIn(name: String) {
        val server = repository.customServers.first().find { it.name == name } ?: return
        repository.beginSignIn(server)
    }

    suspend fun signOut(name: String) {
        val server = allServers().find { it.name == name } ?: return
        repository.clearSession(server.url)
        dropTools(name)
        _statuses.update { it + (name to ServerStatus.NEEDS_AUTH) }
    }

    private suspend fun allServers(): List<McpServer> = defaults + repository.customServers.first()

    private suspend fun connect(server: McpServer) {
        _statuses.update { it + (server.name to ServerStatus.CONNECTING) }
        val status = try {
            val names = connectServer(server)
            setTools(server.name, names)
            if (names.isNotEmpty()) ServerStatus.CONNECTED else ServerStatus.ERROR
        } catch (e: CancellationException) {
            throw e
        } catch (e: McpUnauthorizedException) {
            dropTools(server.name)
            ServerStatus.NEEDS_AUTH
        } catch (e: Exception) {
            logger.w(TAG, "Failed to connect to MCP server: ${server.name}", e)
            dropTools(server.name)
            ServerStatus.ERROR
        }
        _statuses.update { it + (server.name to status) }
    }

    /** Records [names] as [serverName]'s tools, unregistering any it registered before but no longer has. */
    private fun setTools(serverName: String, names: List<String>) {
        val stale = _toolsByServer.value[serverName].orEmpty().map { it.name } - names.toSet()
        stale.forEach { tools.unregister(it) }
        val registered = tools.allIncludingDisabled().associateBy { it.name }
        _toolsByServer.update { it + (serverName to names.mapNotNull { name -> registered[name] }) }
    }

    private fun dropTools(serverName: String) {
        _toolsByServer.value[serverName].orEmpty().forEach { tools.unregister(it.name) }
        _toolsByServer.update { it - serverName }
    }

    private companion object {
        const val TAG = "McpConnections"
    }
}
