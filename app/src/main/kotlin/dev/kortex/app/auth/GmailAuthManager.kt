package dev.kortex.app.auth

import android.accounts.Account
import android.accounts.AccountManager
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Manages Gmail OAuth2 access tokens using Android's built-in [AccountManager].
 *
 * The flow:
 * 1. Call [pickGoogleAccount] to get a chooser Intent → launch it from the Activity.
 * 2. The result gives you the chosen account email → persist it in [McpStore].
 * 3. Call [getToken] with that email to obtain (or refresh) an OAuth2 token
 *    with the `gmail.readonly` scope.
 *
 * On the **first call** per account, Android may return a
 * [KEY_INTENT][AccountManager.KEY_INTENT] that must be launched for the user to
 * grant consent. The caller should handle this Intent (see [AuthResult.NeedsConsent]).
 */
class GmailAuthManager(private val context: Context) {

    private val accountManager: AccountManager = AccountManager.get(context)

    companion object {
        /** OAuth2 scope needed for read-only Gmail access (list + get + attachments). */
        const val GMAIL_SCOPE = "oauth2:https://www.googleapis.com/auth/gmail.readonly"
    }

    /** Returns all Google accounts on the device. */
    fun googleAccounts(): List<Account> =
        accountManager.getAccountsByType("com.google").toList()

    /**
     * Returns an [Intent] that lets the user pick a Google account.
     * Launch this with [Activity.startActivityForResult] or the Activity Result API;
     * the chosen email comes back in `data?.getStringExtra(AccountManager.KEY_ACCOUNT_NAME)`.
     */
    fun pickGoogleAccountIntent(): Intent =
        AccountManager.newChooseAccountIntent(
            /* selectedAccount = */ null,
            /* allowableAccounts = */ null,
            /* allowableAccountTypes = */ arrayOf("com.google"),
            /* descriptionOverrideText = */ "Select the Google account to use for Gmail",
            /* addAccountAuthTokenType = */ null,
            /* addAccountRequiredFeatures = */ null,
            /* addAccountOptions = */ null,
        )

    /** Outcome of [getToken]. */
    sealed interface AuthResult {
        /** A valid access token was obtained. */
        data class Success(val token: String) : AuthResult

        /** The user must grant consent first — launch [intent] and retry. */
        data class NeedsConsent(val intent: Intent) : AuthResult

        /** Something went wrong. */
        data class Error(val message: String, val cause: Throwable? = null) : AuthResult
    }

    /**
     * Obtain an OAuth2 access token for [accountEmail] with the `gmail.readonly` scope.
     *
     * If the token is cached and valid it returns immediately. If user consent is
     * required, returns [AuthResult.NeedsConsent] with the Intent to launch.
     */
    suspend fun getToken(accountEmail: String): AuthResult = withContext(Dispatchers.IO) {
        val account = accountManager.getAccountsByType("com.google")
            .firstOrNull { it.name == accountEmail }
            ?: return@withContext AuthResult.Error("Google account '$accountEmail' not found on device.")

        try {
            val bundle = suspendCancellableCoroutine<Bundle> { cont ->
                @Suppress("DEPRECATION")
                accountManager.getAuthToken(
                    account,
                    GMAIL_SCOPE,
                    /* options = */ null as Bundle?,
                    /* notifyAuthFailure = */ false,
                    /* callback = */ { future ->
                        try {
                            cont.resume(future.result)
                        } catch (e: Exception) {
                            cont.resumeWithException(e)
                        }
                    },
                    /* handler = */ null,
                )
            }

            // If the bundle contains an Intent, the user must grant consent first.
            val consentIntent = bundle.getParcelable<Intent>(AccountManager.KEY_INTENT)
            if (consentIntent != null) {
                return@withContext AuthResult.NeedsConsent(consentIntent)
            }

            val token = bundle.getString(AccountManager.KEY_AUTHTOKEN)
            if (token.isNullOrBlank()) {
                AuthResult.Error("AccountManager returned no token and no consent intent.")
            } else {
                AuthResult.Success(token)
            }
        } catch (e: Exception) {
            AuthResult.Error("Failed to get Gmail token: ${e.message}", e)
        }
    }

    /**
     * Invalidate a cached token so the next [getToken] call fetches a fresh one.
     * Call this when the Gmail API returns 401.
     */
    fun invalidateToken(token: String) {
        accountManager.invalidateAuthToken("com.google", token)
    }
}
