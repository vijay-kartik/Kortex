package dev.kortex.core.mcp

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.client.request.forms.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import java.util.Base64

class McpOAuthTest {

    private fun mockClient(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData): HttpClient {
        return HttpClient(MockEngine) {
            engine {
                addHandler { request -> handler(request) }
            }
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
        }
    }

    @Test
    fun `discover successfully fetches metadata`() = runTest {
        val client = mockClient { request ->
            when (request.url.fullPath) {
                "/.well-known/oauth-protected-resource" -> {
                    respond(
                        """
                        {
                            "authorization_servers": ["https://auth.example.com"],
                            "scopes_supported": ["mcp"]
                        }
                        """.trimIndent(),
                        headers = headersOf(HttpHeaders.ContentType, "application/json")
                    )
                }
                "/.well-known/oauth-authorization-server" -> {
                    respond(
                        """
                        {
                            "authorization_endpoint": "https://auth.example.com/authorize",
                            "token_endpoint": "https://auth.example.com/token",
                            "registration_endpoint": "https://auth.example.com/register",
                            "scopes_supported": ["mcp", "other"]
                        }
                        """.trimIndent(),
                        headers = headersOf(HttpHeaders.ContentType, "application/json")
                    )
                }
                else -> respondError(HttpStatusCode.NotFound)
            }
        }

        val discovery = McpOAuth.discover(
            client = client,
            serverUrl = "https://api.example.com",
            resourceMetadataUrl = null
        )

        discovery.resourceMetadata.authorizationServers shouldBe listOf("https://auth.example.com")
        discovery.authServerMetadata.authorizationEndpoint shouldBe "https://auth.example.com/authorize"
        discovery.scope shouldBe "mcp"
    }

    @Test
    fun `discover falls back to server origin when protected resource metadata 404s`() = runTest {
        val client = mockClient { request ->
            when (request.url.toString()) {
                "https://api.example.com/.well-known/oauth-protected-resource" -> {
                    respondError(HttpStatusCode.NotFound)
                }
                "https://api.example.com/.well-known/oauth-authorization-server" -> {
                    respond(
                        """
                        {
                            "authorization_endpoint": "https://api.example.com/auth",
                            "token_endpoint": "https://api.example.com/token"
                        }
                        """.trimIndent(),
                        headers = headersOf(HttpHeaders.ContentType, "application/json")
                    )
                }
                else -> respondError(HttpStatusCode.NotFound)
            }
        }

        val discovery = McpOAuth.discover(
            client = client,
            serverUrl = "https://api.example.com",
            resourceMetadataUrl = null
        )

        // Null scopes and authorization_servers because it 404'd
        discovery.resourceMetadata.scopesSupported shouldBe null
        discovery.authServerMetadata.authorizationEndpoint shouldBe "https://api.example.com/auth"
        discovery.scope shouldBe null
    }

    @Test
    fun `register successfully fetches client id`() = runTest {
        val client = mockClient { request ->
            respond(
                """
                {
                    "client_id": "test_client_id"
                }
                """.trimIndent(),
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val meta = AuthServerMetadata(
            authorizationEndpoint = "https://auth.example.com/auth",
            tokenEndpoint = "https://auth.example.com/token",
            registrationEndpoint = "https://auth.example.com/register"
        )
        val clientId = McpOAuth.register(meta, "com.example.app:/oauth2redirect", "mcp", client)
        clientId shouldBe "test_client_id"
    }

    @Test
    fun `beginAuthorization constructs correct pending authorization`() {
        val meta = AuthServerMetadata(
            authorizationEndpoint = "https://auth.example.com/auth",
            tokenEndpoint = "https://auth.example.com/token"
        )
        val pending = McpOAuth.beginAuthorization(meta, "test_client_id", "com.example.app:/oauth2redirect", "https://api.example.com", "mcp")
        pending.authorizationUrl shouldNotBe null
        pending.authorizationUrl.contains("response_type=code") shouldBe true
        pending.authorizationUrl.contains("client_id=test_client_id") shouldBe true
        pending.authorizationUrl.contains("resource=https%3A%2F%2Fapi.example.com") shouldBe true
        pending.clientId shouldBe "test_client_id"
        pending.tokenEndpoint shouldBe "https://auth.example.com/token"
        pending.codeVerifier.length shouldNotBe 0
    }

    @Test
    fun `exchangeCode exchanges code for tokens`() = runTest {
        val client = mockClient { request ->
            respond(
                """
                {
                    "access_token": "acc_token_123",
                    "refresh_token": "ref_token_456",
                    "expires_in": 3600,
                    "scope": "mcp"
                }
                """.trimIndent(),
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val tokens = McpOAuth.exchangeCode("https://auth.example.com/token", "test_client_id", "auth_code_789", "verifier_123", "com.example.app:/oauth2redirect", "https://api.example.com", client)
        tokens.accessToken shouldBe "acc_token_123"
        tokens.refreshToken shouldBe "ref_token_456"
        tokens.scope shouldBe "mcp"
        tokens.expiresAtMillis shouldNotBe null
    }

    @Test
    fun `refresh trades refresh token for new tokens`() = runTest {
        val client = mockClient { request ->
            respond(
                """
                {
                    "access_token": "acc_token_new",
                    "expires_in": 3600
                }
                """.trimIndent(),
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val tokens = McpOAuth.refresh("https://auth.example.com/token", "test_client_id", "ref_token_old", "https://api.example.com", client)
        tokens.accessToken shouldBe "acc_token_new"
        tokens.refreshToken shouldBe "ref_token_old" // fallback to old refresh token
        tokens.expiresAtMillis shouldNotBe null
    }
}
