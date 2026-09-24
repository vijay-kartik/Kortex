package dev.kortex.app.ui.security

import android.app.KeyguardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

enum class BiometricAvailability { AVAILABLE, NONE_ENROLLED, UNSUPPORTED }

sealed interface AuthResult {
    data object Success : AuthResult

    /** The prompt closed without success; [message] is null for a plain cancel. */
    data class Dismissed(val message: String?) : AuthResult
}

/** Thin wrapper over androidx BiometricPrompt, using only what's already registered on the phone. */
object Biometrics {

    private const val BIOMETRIC_OR_CREDENTIAL = BIOMETRIC_WEAK or DEVICE_CREDENTIAL

    fun availability(context: Context): BiometricAvailability =
        when (BiometricManager.from(context).canAuthenticate(BIOMETRIC_WEAK)) {
            BiometricManager.BIOMETRIC_SUCCESS -> BiometricAvailability.AVAILABLE
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> BiometricAvailability.NONE_ENROLLED
            else -> BiometricAvailability.UNSUPPORTED
        }

    /** No screen lock at all (so no biometrics either): nothing on the phone could unlock Kortex. */
    fun deviceUnsecured(context: Context): Boolean =
        context.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == false

    /** Android's add-fingerprint/face screen on 11+, else the Security settings screen. */
    fun enrollIntent(): Intent =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(Settings.ACTION_BIOMETRIC_ENROLL)
                .putExtra(Settings.EXTRA_BIOMETRIC_AUTHENTICATORS_ALLOWED, BIOMETRIC_WEAK)
        } else {
            Intent(Settings.ACTION_SECURITY_SETTINGS)
        }

    /**
     * Shows the system prompt. With [allowDeviceCredential] the prompt offers "Use PIN" and has no
     * Cancel button (Android forbids both); otherwise it accepts biometrics only.
     */
    fun authenticate(
        activity: FragmentActivity,
        title: String,
        subtitle: String,
        allowDeviceCredential: Boolean,
        onResult: (AuthResult) -> Unit,
    ) {
        var failedAttempts = 0
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) =
                onResult(AuthResult.Success)

            // The prompt stays open after a miss; remember it for the message once it closes.
            override fun onAuthenticationFailed() {
                failedAttempts++
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                val message = when (errorCode) {
                    BiometricPrompt.ERROR_USER_CANCELED,
                    BiometricPrompt.ERROR_NEGATIVE_BUTTON,
                    BiometricPrompt.ERROR_CANCELED,
                    -> if (failedAttempts > 0) "Not recognised. Try again." else null
                    else -> errString.toString()
                }
                onResult(AuthResult.Dismissed(message))
            }
        }
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setConfirmationRequired(false)
            .apply {
                if (allowDeviceCredential) {
                    setAllowedAuthenticators(BIOMETRIC_OR_CREDENTIAL)
                } else {
                    setAllowedAuthenticators(BIOMETRIC_WEAK)
                    setNegativeButtonText("Cancel")
                }
            }
            .build()
        BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), callback).authenticate(info)
    }
}

internal tailrec fun Context.findFragmentActivity(): FragmentActivity? = when (this) {
    is FragmentActivity -> this
    is ContextWrapper -> baseContext.findFragmentActivity()
    else -> null
}
