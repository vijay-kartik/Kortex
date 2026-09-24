package dev.kortex.app.domain.security

import android.os.SystemClock
import dev.kortex.app.data.security.AppLockStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** How long Kortex may sit in the background before it locks again. */
enum class LockAfter(val label: String, val millis: Long) {
    IMMEDIATELY("Immediately", 0),
    ONE_MINUTE("1 min", 60_000),
    FIVE_MINUTES("5 min", 5 * 60_000),
    FIFTEEN_MINUTES("15 min", 15 * 60_000),
}

data class AppLockSettings(
    val enabled: Boolean = false,
    val lockAfter: LockAfter = LockAfter.IMMEDIATELY,
    /** Blanks the recent-apps preview and blocks screenshots while app lock is on. */
    val hideInRecents: Boolean = true,
)

/**
 * App-wide lock state. Starts locked on a cold start when app lock is on, and locks again when
 * the app comes back after [AppLockSettings.lockAfter] in the background. KortexApp feeds it the
 * process lifecycle; every activity draws behind `AppLockGate`, which does the unlocking.
 */
class AppLock(
    private val store: AppLockStore,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
) {
    val settings: StateFlow<AppLockSettings> = store.settings

    private val _locked = MutableStateFlow(store.settings.value.enabled)
    val locked: StateFlow<Boolean> = _locked.asStateFlow()

    /** Bumped each time the app returns to the foreground locked, so the gate re-opens the prompt. */
    private val _promptRequests = MutableStateFlow(0)
    val promptRequests: StateFlow<Int> = _promptRequests.asStateFlow()

    /**
     * True while a biometric or PIN prompt is up. The PIN screen on older Android is a separate
     * activity, so leaving for it must not count as leaving the app.
     */
    @Volatile var authInProgress = false

    private var backgroundedAt: Long? = null

    fun onAppBackgrounded() {
        if (!authInProgress) backgroundedAt = clock()
    }

    fun onAppForegrounded() {
        val since = backgroundedAt ?: return
        backgroundedAt = null
        val current = settings.value
        if (!current.enabled) return
        if (_locked.value || clock() - since >= current.lockAfter.millis) {
            _locked.value = true
            _promptRequests.update { it + 1 }
        }
    }

    fun unlock() {
        _locked.value = false
    }

    /** Callers confirm with biometrics first; turning it on doesn't lock the session in progress. */
    fun setEnabled(enabled: Boolean) {
        store.update { it.copy(enabled = enabled) }
        if (!enabled) _locked.value = false
    }

    fun setLockAfter(lockAfter: LockAfter) = store.update { it.copy(lockAfter = lockAfter) }

    fun setHideInRecents(hide: Boolean) = store.update { it.copy(hideInRecents = hide) }
}
