package dev.kortex.app.domain.agent

import dev.kortex.core.log.Logger
import dev.kortex.core.mcp.McpServer
import dev.kortex.core.mcp.McpUnauthorizedException
import dev.kortex.core.tool.Tool
import dev.kortex.core.tool.ToolRegistry
import dev.kortex.core.tool.ToolResult
import dev.kortex.core.tool.ToolSchema
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [McpConnections] against an in-memory server list and a fake connector that registers canned tools. */
class McpConnectionsTest {

    private val registry = ToolRegistry(listOf(FakeTool("calculator"), FakeTool("add_expense")))
    private val repository = FakeMcpServerRepository()

    /** Tool names each server URL registers; a URL in [unauthorized] throws instead. */
    private val toolsByUrl = mutableMapOf<String, List<String>>()
    private val unauthorized = mutableSetOf<String>()

    private val default = McpServer(name = "deepwiki", url = "https://deepwiki.test/mcp")

    private val connections = McpConnections(
        tools = registry,
        repository = repository,
        connectServer = { server ->
            if (server.url in unauthorized) throw McpUnauthorizedException(null)
            toolsByUrl[server.url].orEmpty().onEach { registry.register(FakeTool(it)) }
        },
        defaults = listOf(default),
        logger = Logger.NONE,
    )

    @Test
    fun `connecting records each server's tools and status`() = runBlocking {
        toolsByUrl[default.url] = listOf("deepwiki_ask")
        repository.servers.value = listOf(McpServer("linear", "https://linear.test/mcp"))
        toolsByUrl["https://linear.test/mcp"] = listOf("linear_create_issue", "linear_search")

        connections.connectAll()

        assertEquals(ServerStatus.CONNECTED, connections.statuses.value["deepwiki"])
        assertEquals(ServerStatus.CONNECTED, connections.statuses.value["linear"])
        assertEquals(listOf("deepwiki_ask"), connections.toolsByServer.value["deepwiki"]?.map { it.name })
        assertEquals(
            listOf("linear_create_issue", "linear_search"),
            connections.toolsByServer.value["linear"]?.map { it.name },
        )
    }

    @Test
    fun `builtins are every registered tool no server owns`() = runBlocking {
        toolsByUrl[default.url] = listOf("deepwiki_ask")

        connections.connectAll()

        assertEquals(listOf("calculator", "add_expense"), connections.builtinTools().map { it.name })
    }

    @Test
    fun `a server with no tools is an error`() = runBlocking {
        connections.connectAll()

        assertEquals(ServerStatus.ERROR, connections.statuses.value["deepwiki"])
        assertEquals(emptyList<Tool>(), connections.toolsByServer.value["deepwiki"].orEmpty())
    }

    @Test
    fun `an unauthorized server needs auth`() = runBlocking {
        unauthorized += default.url

        connections.connectAll()

        assertEquals(ServerStatus.NEEDS_AUTH, connections.statuses.value["deepwiki"])
        assertNull(connections.toolsByServer.value["deepwiki"])
    }

    @Test
    fun `reconnecting drops tools the server no longer has`() = runBlocking {
        toolsByUrl[default.url] = listOf("deepwiki_ask", "deepwiki_read")
        connections.connectAll()

        toolsByUrl[default.url] = listOf("deepwiki_ask")
        connections.connectAll()

        assertEquals(listOf("deepwiki_ask"), connections.toolsByServer.value["deepwiki"]?.map { it.name })
        assertFalse(registry.allIncludingDisabled().any { it.name == "deepwiki_read" })
    }

    @Test
    fun `adding a server saves and connects it`() = runBlocking {
        toolsByUrl["https://linear.test/mcp"] = listOf("linear_search")

        connections.add("linear", "https://linear.test/mcp", bearerToken = "secret")

        assertEquals(listOf("linear"), repository.servers.value.map { it.name })
        assertEquals("secret", repository.servers.value.single().bearerToken)
        assertEquals(ServerStatus.CONNECTED, connections.statuses.value["linear"])
        assertTrue(registry.allIncludingDisabled().any { it.name == "linear_search" })
    }

    @Test
    fun `signing out clears the session, unregisters the tools and needs auth`() = runBlocking {
        toolsByUrl[default.url] = listOf("deepwiki_ask")
        repository.tokens.value = mapOf(default.url to "token")
        connections.connectAll()

        connections.signOut("deepwiki")

        assertEquals(ServerStatus.NEEDS_AUTH, connections.statuses.value["deepwiki"])
        assertNull(connections.toolsByServer.value["deepwiki"])
        assertFalse(registry.allIncludingDisabled().any { it.name == "deepwiki_ask" })
        assertEquals(emptySet<String>(), connections.signedInUrls.first())
    }

    @Test
    fun `deleting a server forgets it and unregisters its tools`() = runBlocking {
        repository.servers.value = listOf(McpServer("linear", "https://linear.test/mcp"))
        toolsByUrl["https://linear.test/mcp"] = listOf("linear_search")
        connections.connectAll()

        connections.remove("linear")

        assertNull(connections.statuses.value["linear"])
        assertNull(connections.toolsByServer.value["linear"])
        assertFalse(registry.allIncludingDisabled().any { it.name == "linear_search" })
        assertEquals(listOf("deepwiki"), connections.servers.first().map { it.name })
    }

    @Test
    fun `signing in starts the OAuth flow for a user-added server only`() = runBlocking {
        repository.servers.value = listOf(McpServer("linear", "https://linear.test/mcp"))

        connections.signIn("linear")
        connections.signIn("deepwiki")

        assertEquals(listOf("linear"), repository.signInsStarted)
    }

    private class FakeMcpServerRepository : McpServerRepository {
        val servers = MutableStateFlow<List<McpServer>>(emptyList())
        val tokens = MutableStateFlow<Map<String, String>>(emptyMap())
        val signInsStarted = mutableListOf<String>()

        override val customServers = servers
        override val sessionTokens = tokens

        override suspend fun addServer(name: String, url: String, bearerToken: String?) {
            servers.update { it + McpServer(name, url, bearerToken) }
        }

        override suspend fun removeServer(name: String) {
            servers.update { list -> list.filterNot { it.name == name } }
        }

        override suspend fun clearSession(url: String) {
            tokens.update { it - url }
        }

        override suspend fun beginSignIn(server: McpServer) {
            signInsStarted += server.name
        }
    }

    private class FakeTool(override val name: String) : Tool {
        override val description = name
        override val parameters = ToolSchema(emptyList())
        override suspend fun execute(args: JsonObject) = ToolResult(true, name)
    }
}
