package dev.kortex.finance.ui.common

import android.app.KeyguardManager
import android.content.Context
import android.content.ContextWrapper
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/**
 * The phone's own lock — fingerprint, face or PIN — asked for before a full card or account number
 * shows (docs/FINANCE_PLAN.md › Decisions). No lock set means no full numbers.
 */
object ScreenLock {
    fun confirm(context: Context, title: String, onSuccess: () -> Unit, onFailure: (String?) -> Unit) {
        if (context.getSystemService(KeyguardManager::class.java)?.isDeviceSecure != true) {
            onFailure("Set a screen lock on this phone to see full numbers.")
            return
        }
        val activity = context.findFragmentActivity() ?: return onFailure("Couldn’t ask for your screen lock here.")
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onSuccess()

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = onFailure(
                when (errorCode) {
                    BiometricPrompt.ERROR_USER_CANCELED, BiometricPrompt.ERROR_CANCELED, BiometricPrompt.ERROR_NEGATIVE_BUTTON -> null
                    else -> errString.toString()
                },
            )
        }
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle("Full numbers only show after your screen lock")
            .setAllowedAuthenticators(BIOMETRIC_WEAK or DEVICE_CREDENTIAL)
            .setConfirmationRequired(false)
            .build()
        BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), callback).authenticate(info)
    }

    /** "4111111111118824" → "4111 1111 1111 8824". */
    fun grouped(number: String): String = number.chunked(4).joinToString(" ")

    /** How long a revealed number stays on screen. */
    const val SHOW_MILLIS = 30_000L
}

private tailrec fun Context.findFragmentActivity(): FragmentActivity? = when (this) {
    is FragmentActivity -> this
    is ContextWrapper -> baseContext.findFragmentActivity()
    else -> null
}
