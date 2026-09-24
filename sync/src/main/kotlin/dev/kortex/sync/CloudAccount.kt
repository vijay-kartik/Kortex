package dev.kortex.sync

import android.app.Activity
import android.content.Context
import android.util.Log
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CredentialOption
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.ClearCredentialException
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await

/** The signed-in Firebase user, trimmed to what the UI shows. */
data class CloudUser(val uid: String, val name: String?, val email: String?)

sealed interface SignInResult {
    data class Success(val user: CloudUser) : SignInResult

    /** The user closed the account picker. Not an error. */
    data object Cancelled : SignInResult

    data class Failed(val message: String) : SignInResult
}

/**
 * The Kortex cloud account: Google sign-in through Credential Manager, exchanged for a Firebase
 * Auth session. Firebase persists the session, so [user] is already known on a cold start.
 *
 * [webClientId] is Firebase Auth's *web* OAuth client id; Credential Manager rejects the Android one.
 */
class CloudAccount(context: Context, private val webClientId: String) {

    private val auth = FirebaseAuth.getInstance()
    private val credentials = CredentialManager.create(context)

    private val _user = MutableStateFlow(auth.currentUser?.toCloudUser())
    val user: StateFlow<CloudUser?> = _user.asStateFlow()

    init {
        auth.addAuthStateListener { _user.value = it.currentUser?.toCloudUser() }
    }

    /** Shows the Google account picker over [activity], then signs into Firebase with the chosen account. */
    suspend fun signIn(activity: Activity): SignInResult {
        if (webClientId.isBlank()) return SignInResult.Failed("Google sign-in isn’t set up in this build.")
        return try {
            val idToken = try {
                requestIdToken(
                    activity,
                    GetGoogleIdOption.Builder()
                        .setServerClientId(webClientId)
                        .setFilterByAuthorizedAccounts(false)
                        .build(),
                )
            } catch (e: NoCredentialException) {
                // No Google account on the phone yet; this flow lets the user add one.
                requestIdToken(activity, GetSignInWithGoogleOption.Builder(webClientId).build())
            }
            val session = auth.signInWithCredential(GoogleAuthProvider.getCredential(idToken, null)).await()
            session.user?.let { SignInResult.Success(it.toCloudUser()) } ?: SignInResult.Failed(GENERIC_FAILURE)
        } catch (e: GetCredentialCancellationException) {
            SignInResult.Cancelled
        } catch (e: CancellationException) {
            throw e
        } catch (e: FirebaseNetworkException) {
            SignInResult.Failed(NETWORK_FAILURE)
        } catch (e: Exception) {
            Log.w(TAG, "Google sign-in failed", e)
            SignInResult.Failed(GENERIC_FAILURE)
        }
    }

    /**
     * Signs out of Firebase and forgets the chosen Google account, so the next sign-in shows the
     * account picker again. Local links and topics are left alone.
     */
    suspend fun signOut() {
        auth.signOut()
        try {
            credentials.clearCredentialState(ClearCredentialStateRequest())
        } catch (e: ClearCredentialException) {
            // The Firebase session is already gone; at worst the picker preselects the old account.
            Log.w(TAG, "Couldn't clear the remembered Google account", e)
        }
    }

    private suspend fun requestIdToken(activity: Activity, option: CredentialOption): String {
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
        val credential = credentials.getCredential(activity, request).credential
        check(credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
            "Unexpected credential type ${credential.type}"
        }
        return GoogleIdTokenCredential.createFrom(credential.data).idToken
    }

    private fun FirebaseUser.toCloudUser() = CloudUser(uid = uid, name = displayName, email = email)

    private companion object {
        const val TAG = "CloudAccount"
        const val NETWORK_FAILURE = "Couldn’t reach Google. Check your connection and try again."
        const val GENERIC_FAILURE = "Sign-in didn’t finish. Try again in a moment."
    }
}
