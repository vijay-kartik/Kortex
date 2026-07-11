package dev.kortex.core.mcp

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

@Serializable
data class ProtectedResourceMetadata(
    @SerialName("authorization_servers") val authorizationServers: List<String>? = null,
    @SerialName("scopes_supported") val scopesSupported: List<String>? = null
)

@Serializable
data class AuthServerMetadata(
    @SerialName("authorization_endpoint") val authorizationEndpoint: String,
    @SerialName("token_endpoint") val tokenEndpoint: String,
    @SerialName("registration_endpoint") val registrationEndpoint: String? = null,
    @SerialName("scopes_supported") val scopesSupported: List<String>? = null
)

@Serializable
data class ClientRegistration(
    @SerialName("client_id") val clientId: String,
    @SerialName("client_secret") val clientSecret: String? = null
)

@Serializable
private data class TokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("expires_in") val expiresIn: Long? = null,
    @SerialName("scope") val scope: String? = null
)

@Serializable
data class OAuthTokens(
    val accessToken: String,
    val refreshToken: String?,
    val expiresAtMillis: Long?,
    val scope: String?
)

@Serializable
data class PendingAuthorization(
    val authorizationUrl: String,
    val state: String,
    val codeVerifier: String,
    val tokenEndpoint: String,
    val clientId: String,
    val serverUrl: String
)

data class McpOAuthDiscovery(
    val resourceMetadata: ProtectedResourceMetadata,
    val authServerMetadata: AuthServerMetadata,
    val scope: String?
)

@Serializable
private data class ClientRegistrationRequest(
    @SerialName("client_name") val clientName: String = "Kortex",
    @SerialName("redirect_uris") val redirectUris: List<String>,
    @SerialName("grant_types") val grantTypes: List<String> = listOf("authorization_code", "refresh_token"),
    @SerialName("response_types") val responseTypes: List<String> = listOf("code"),
    @SerialName("token_endpoint_auth_method") val tokenEndpointAuthMethod: String = "none",
    @SerialName("scope") val scope: String
)

object McpOAuth {
    private val json = Json { ignoreUnknownKeys = true }
    private val secureRandom = SecureRandom()

    suspend fun discover(
        serverUrl: String,
        resourceMetadataUrl: String? = null,
        client: HttpClient = defaultMcpHttpClient()
    ): McpOAuthDiscovery {
        val serverUrlParsed = Url(serverUrl)
        val origin = "${serverUrlParsed.protocol.name}://${serverUrlParsed.host}${
            if (serverUrlParsed.port != serverUrlParsed.protocol.defaultPort) ":${serverUrlParsed.port}" else ""
        }"

        val resourceUrl = resourceMetadataUrl ?: "$origin/.well-known/oauth-protected-resource"
        
        var protectedResourceMeta = ProtectedResourceMetadata()
        var authServerBase = origin

        val resourceResponse = client.get(resourceUrl)
        if (resourceResponse.status.isSuccess()) {
            protectedResourceMeta = json.decodeFromString(resourceResponse.bodyAsText())
            if (!protectedResourceMeta.authorizationServers.isNullOrEmpty()) {
                authServerBase = protectedResourceMeta.authorizationServers.first()
            }
        }

        // Auth server metadata
        val authBaseUrl = authServerBase.trimEnd('/')
        var authResponse = client.get("$authBaseUrl/.well-known/oauth-authorization-server")
        if (authResponse.status == HttpStatusCode.NotFound) {
            authResponse = client.get("$authBaseUrl/.well-known/openid-configuration")
        }

        if (!authResponse.status.isSuccess()) {
            throw McpException("Failed to discover authorization server metadata")
        }

        val authServerMeta = json.decodeFromString<AuthServerMetadata>(authResponse.bodyAsText())
        val selectedScope = protectedResourceMeta.scopesSupported?.joinToString(" ")
            ?: authServerMeta.scopesSupported?.joinToString(" ")
            
        return McpOAuthDiscovery(protectedResourceMeta, authServerMeta, selectedScope)
    }

    suspend fun register(
        meta: AuthServerMetadata,
        redirectUri: String,
        scope: String,
        client: HttpClient = defaultMcpHttpClient()
    ): String {
        val regEndpoint = meta.registrationEndpoint ?: throw McpException("No registration_endpoint found")
        val response = client.post(regEndpoint) {
            contentType(ContentType.Application.Json)
            val requestBody = ClientRegistrationRequest(
                redirectUris = listOf(redirectUri),
                scope = scope
            )
            setBody(json.encodeToString(ClientRegistrationRequest.serializer(), requestBody))
        }
        if (!response.status.isSuccess()) {
            throw McpException("Registration failed: ${response.status}")
        }
        val reg = json.decodeFromString<ClientRegistration>(response.bodyAsText())
        return reg.clientId
    }

    fun beginAuthorization(
        meta: AuthServerMetadata,
        clientId: String,
        redirectUri: String,
        resource: String,
        scope: String
    ): PendingAuthorization {
        val verifierBytes = ByteArray(32)
        secureRandom.nextBytes(verifierBytes)
        val codeVerifier = Base64.getUrlEncoder().withoutPadding().encodeToString(verifierBytes)
        val codeChallenge = generateCodeChallenge(codeVerifier)

        val stateBytes = ByteArray(16)
        secureRandom.nextBytes(stateBytes)
        val state = Base64.getUrlEncoder().withoutPadding().encodeToString(stateBytes)

        val authUrl = URLBuilder(meta.authorizationEndpoint).apply {
            parameters.append("response_type", "code")
            parameters.append("client_id", clientId)
            parameters.append("redirect_uri", redirectUri)
            parameters.append("scope", scope)
            parameters.append("state", state)
            parameters.append("code_challenge", codeChallenge)
            parameters.append("code_challenge_method", "S256")
            parameters.append("resource", resource)
        }.buildString()

        return PendingAuthorization(
            authorizationUrl = authUrl,
            state = state,
            codeVerifier = codeVerifier,
            tokenEndpoint = meta.tokenEndpoint,
            clientId = clientId,
            serverUrl = resource
        )
    }

    suspend fun exchangeCode(
        tokenEndpoint: String,
        clientId: String,
        code: String,
        codeVerifier: String,
        redirectUri: String,
        resource: String,
        client: HttpClient = defaultMcpHttpClient()
    ): OAuthTokens {
        val response = client.submitForm(
            url = tokenEndpoint,
            formParameters = parameters {
                append("grant_type", "authorization_code")
                append("client_id", clientId)
                append("code", code)
                append("code_verifier", codeVerifier)
                append("redirect_uri", redirectUri)
                append("resource", resource)
            }
        )
        if (!response.status.isSuccess()) {
            throw McpException("Token exchange failed: ${response.status}")
        }
        val tokenResponse = json.decodeFromString<TokenResponse>(response.bodyAsText())
        val expiresAt = tokenResponse.expiresIn?.let { System.currentTimeMillis() + (it * 1000) - 60_000 }
        
        return OAuthTokens(
            accessToken = tokenResponse.accessToken,
            refreshToken = tokenResponse.refreshToken,
            expiresAtMillis = expiresAt,
            scope = tokenResponse.scope
        )
    }

    suspend fun refresh(
        tokenEndpoint: String,
        clientId: String,
        refreshToken: String,
        resource: String,
        client: HttpClient = defaultMcpHttpClient()
    ): OAuthTokens {
        val response = client.submitForm(
            url = tokenEndpoint,
            formParameters = parameters {
                append("grant_type", "refresh_token")
                append("client_id", clientId)
                append("refresh_token", refreshToken)
                append("resource", resource)
            }
        )
        if (!response.status.isSuccess()) {
            throw McpException("Token refresh failed: ${response.status}")
        }
        val tokenResponse = json.decodeFromString<TokenResponse>(response.bodyAsText())
        val expiresAt = tokenResponse.expiresIn?.let { System.currentTimeMillis() + (it * 1000) - 60_000 }
        
        return OAuthTokens(
            accessToken = tokenResponse.accessToken,
            refreshToken = tokenResponse.refreshToken ?: refreshToken,
            expiresAtMillis = expiresAt,
            scope = tokenResponse.scope
        )
    }

    internal fun generateCodeChallenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val challengeBytes = digest.digest(verifier.toByteArray(Charsets.US_ASCII))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(challengeBytes)
    }
}
