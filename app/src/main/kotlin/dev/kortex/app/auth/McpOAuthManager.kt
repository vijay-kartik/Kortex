package dev.kortex.app.auth

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import dev.kortex.app.McpOAuthState
import dev.kortex.app.McpStore
import dev.kortex.core.mcp.McpOAuth
import dev.kortex.core.mcp.McpServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class McpOAuthManager(
    private val context: Context,
    private val mcpStore: McpStore,
    private val onReconnect: (String) -> Unit = {}
) {
    companion object {
        const val REDIRECT_URI = "kortex://oauth/callback"
    }

    private val refreshMutex = Mutex()

    suspend fun beginSignIn(server: McpServer) {
        val storedState = mcpStore.oauthStates.first()[server.url]
        val discovery = McpOAuth.discover(server.url)
        val authMeta = discovery.authServerMetadata

        val clientId = storedState?.clientId ?: McpOAuth.register(
            meta = authMeta,
            redirectUri = REDIRECT_URI,
            scope = discovery.scope ?: ""
        )

        val pendingAuth = McpOAuth.beginAuthorization(
            meta = authMeta,
            clientId = clientId,
            redirectUri = REDIRECT_URI,
            resource = server.url,
            scope = discovery.scope ?: ""
        )

        mcpStore.setPendingAuth(pendingAuth)

        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(pendingAuth.authorizationUrl)).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
    }

    suspend fun handleCallback(uri: Uri) {
        val pendingAuth = mcpStore.consumePendingAuth()
        if (pendingAuth == null) {
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "No pending authorization found", Toast.LENGTH_SHORT).show()
            }
            return
        }

        val state = uri.getQueryParameter("state")
        if (state != pendingAuth.state) {
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "State mismatch, authorization aborted", Toast.LENGTH_SHORT).show()
            }
            return
        }

        val code = uri.getQueryParameter("code")
        if (code == null) {
            val error = uri.getQueryParameter("error") ?: "Authorization failed"
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "OAuth error: $error", Toast.LENGTH_SHORT).show()
            }
            return
        }

        try {
            val tokens = McpOAuth.exchangeCode(
                tokenEndpoint = pendingAuth.tokenEndpoint,
                clientId = pendingAuth.clientId,
                code = code,
                codeVerifier = pendingAuth.codeVerifier,
                redirectUri = REDIRECT_URI,
                resource = pendingAuth.serverUrl
            )

            val oauthState = McpOAuthState(
                clientId = pendingAuth.clientId,
                accessToken = tokens.accessToken,
                refreshToken = tokens.refreshToken,
                expiresAtMillis = tokens.expiresAtMillis,
                tokenEndpoint = pendingAuth.tokenEndpoint
            )
            mcpStore.setOauthState(pendingAuth.serverUrl, oauthState)
            onReconnect(pendingAuth.serverUrl)
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "Exchange failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun tokenProviderFor(url: String): suspend (forceRefresh: Boolean) -> String? {
        return { forceRefresh ->
            val currentState = mcpStore.oauthStates.first()[url]
            if (currentState == null) {
                null
            } else {
                val now = System.currentTimeMillis()
                val needsRefresh = forceRefresh || (currentState.expiresAtMillis != null && currentState.expiresAtMillis < now)

                if (needsRefresh && currentState.refreshToken != null) {
                    refreshMutex.withLock {
                        // Double-checked locking
                        val stateDuringLock = mcpStore.oauthStates.first()[url]
                        if (stateDuringLock?.accessToken != currentState.accessToken && stateDuringLock?.accessToken != null) {
                            // Another thread already refreshed
                            stateDuringLock.accessToken
                        } else {
                            try {
                                val tokens = McpOAuth.refresh(
                                    tokenEndpoint = currentState.tokenEndpoint,
                                    clientId = currentState.clientId,
                                    refreshToken = currentState.refreshToken,
                                    resource = url
                                )

                                val newState = currentState.copy(
                                    accessToken = tokens.accessToken,
                                    refreshToken = tokens.refreshToken,
                                    expiresAtMillis = tokens.expiresAtMillis
                                )
                                mcpStore.setOauthState(url, newState)
                                newState.accessToken
                            } catch (e: Exception) {
                                mcpStore.setOauthState(url, null)
                                null
                            }
                        }
                    }
                } else {
                    currentState.accessToken
                }
            }
        }
    }
}
