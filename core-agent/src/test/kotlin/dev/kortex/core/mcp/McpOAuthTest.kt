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
import kotlin.math.abs

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
    fun `metadata parsing handles real TranscriptMagic payloads`() = runTest {
        val client = mockClient { request ->
            when (request.url.fullPath) {
                "/.well-known/oauth-protected-resource" -> {
                    respond(
                        """
                        {
                          "resource": "https://api.transcriptmagic.com",
                          "authorization_servers": [
                            "https://auth.transcriptmagic.com"
                          ],
                          "scopes_supported": [
                            "mcp"
                          ],
                          "bearer_methods_supported": [
                            "header"
                          ]
                        }
                        """.trimIndent(),
                        headers = headersOf(HttpHeaders.ContentType, "application/json")
                    )
                }
                "/.well-known/oauth-authorization-server" -> {
                    respond(
                        """
                        {
                          "issuer": "https://auth.transcriptmagic.com",
                          "authorization_endpoint": "https://auth.transcriptmagic.com/oauth2/authorize",
                          "token_endpoint": "https://auth.transcriptmagic.com/oauth2/token",
                          "jwks_uri": "https://auth.transcriptmagic.com/.well-known/jwks.json",
                          "scopes_supported": [
                            "mcp",
                            "offline_access"
                          ],
                          "response_types_supported": [
                            "code"
                          ],
                          "grant_types_supported": [
                            "authorization_code",
                            "refresh_token"
                          ],
                          "code_challenge_methods_supported": [
                            "S256"
                          ]
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
            serverUrl = "https://api.transcriptmagic.com",
            resourceMetadataUrl = null
        )

        discovery.resourceMetadata.authorizationServers shouldBe listOf("https://auth.transcriptmagic.com")
        discovery.resourceMetadata.scopesSupported shouldBe listOf("mcp")
        discovery.authServerMetadata.authorizationEndpoint shouldBe "https://auth.transcriptmagic.com/oauth2/authorize"
        discovery.authServerMetadata.tokenEndpoint shouldBe "https://auth.transcriptmagic.com/oauth2/token"
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
        pending.authorizationUrl.contains("redirect_uri=com.example.app%3A%2Foauth2redirect") shouldBe true
        pending.authorizationUrl.contains("scope=mcp") shouldBe true
        pending.authorizationUrl.contains("state=${pending.state}") shouldBe true
        pending.authorizationUrl.contains("code_challenge=") shouldBe true
        pending.authorizationUrl.contains("code_challenge_method=S256") shouldBe true
        pending.clientId shouldBe "test_client_id"
        pending.tokenEndpoint shouldBe "https://auth.example.com/token"
        
        // verifier charset/length check
        pending.codeVerifier.length shouldBe 43 // 32 bytes base64url encoded without padding is 43 chars
        pending.codeVerifier.matches(Regex("^[a-zA-Z0-9\\-._~]+$")) shouldBe true // PKCE unreserved characters
    }

    @Test
    fun `generateCodeChallenge produces correct RFC 7636 Appendix B vector`() {
        val verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
        val expectedChallenge = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"
        
        val actualChallenge = McpOAuth.generateCodeChallenge(verifier)
        actualChallenge shouldBe expectedChallenge
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
        
        // Check expires_in skew
        tokens.expiresAtMillis shouldNotBe null
        val expectedExpiresAt = System.currentTimeMillis() + (3600 * 1000) - 60_000
        (abs(tokens.expiresAtMillis!! - expectedExpiresAt) < 1000L) shouldBe true
    }

    @Test
    fun `exchangeCode sends correct body parameters`() = runTest {
        var requestBody: String? = null
        val client = mockClient { request ->
            requestBody = (request.body as FormDataContent).formData.formUrlEncode()
            respond(
                """
                {
                    "access_token": "acc_token_123"
                }
                """.trimIndent(),
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        McpOAuth.exchangeCode(
            "https://auth.example.com/token", 
            "test_client_id", 
            "auth_code_789", 
            "verifier_123", 
            "com.example.app:/oauth2redirect", 
            "https://api.example.com", 
            client
        )
        
        requestBody shouldNotBe null
        requestBody!!.contains("grant_type=authorization_code") shouldBe true
        requestBody!!.contains("client_id=test_client_id") shouldBe true
        requestBody!!.contains("code=auth_code_789") shouldBe true
        requestBody!!.contains("code_verifier=verifier_123") shouldBe true
        requestBody!!.contains("redirect_uri=com.example.app%3A%2Foauth2redirect") shouldBe true
        requestBody!!.contains("resource=https%3A%2F%2Fapi.example.com") shouldBe true
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
        
        // Check expires_in skew
        tokens.expiresAtMillis shouldNotBe null
        val expectedExpiresAt = System.currentTimeMillis() + (3600 * 1000) - 60_000
        (abs(tokens.expiresAtMillis!! - expectedExpiresAt) < 1000L) shouldBe true
    }

    @Test
    fun `refresh sends correct body parameters`() = runTest {
        var requestBody: String? = null
        val client = mockClient { request ->
            requestBody = (request.body as FormDataContent).formData.formUrlEncode()
            respond(
                """
                {
                    "access_token": "acc_token_new"
                }
                """.trimIndent(),
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        McpOAuth.refresh("https://auth.example.com/token", "test_client_id", "ref_token_old", "https://api.example.com", client)
        
        requestBody shouldNotBe null
        requestBody!!.contains("grant_type=refresh_token") shouldBe true
        requestBody!!.contains("client_id=test_client_id") shouldBe true
        requestBody!!.contains("refresh_token=ref_token_old") shouldBe true
        requestBody!!.contains("resource=https%3A%2F%2Fapi.example.com") shouldBe true
    }
}
