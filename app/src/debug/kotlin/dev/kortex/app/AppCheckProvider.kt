package dev.kortex.app

import com.google.firebase.appcheck.AppCheckProviderFactory
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory

/**
 * Debug builds attest with App Check's debug provider: the first run logs a debug token, which is
 * added under App Check › Apps › Manage debug tokens in the Firebase console.
 */
internal fun appCheckProviderFactory(): AppCheckProviderFactory = DebugAppCheckProviderFactory.getInstance()
