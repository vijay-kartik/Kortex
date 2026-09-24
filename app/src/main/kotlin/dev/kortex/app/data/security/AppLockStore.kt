package dev.kortex.app.data.security

import android.content.Context
import dev.kortex.app.domain.security.AppLockSettings
import dev.kortex.app.domain.security.LockAfter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.updateAndGet

/**
 * App-lock settings, kept on this device only. SharedPreferences rather than DataStore because
 * a cold start must know synchronously whether to lock before anything is drawn.
 */
class AppLockStore(context: Context) {

    private val prefs = context.getSharedPreferences("app_lock", Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<AppLockSettings> = _settings.asStateFlow()

    fun update(transform: (AppLockSettings) -> AppLockSettings) {
        val next = _settings.updateAndGet(transform)
        prefs.edit()
            .putBoolean(KEY_ENABLED, next.enabled)
            .putString(KEY_LOCK_AFTER, next.lockAfter.name)
            .putBoolean(KEY_HIDE_IN_RECENTS, next.hideInRecents)
            .apply()
    }

    private fun load(): AppLockSettings {
        val defaults = AppLockSettings()
        return AppLockSettings(
            enabled = prefs.getBoolean(KEY_ENABLED, defaults.enabled),
            lockAfter = prefs.getString(KEY_LOCK_AFTER, null)
                ?.let { name -> LockAfter.entries.firstOrNull { it.name == name } }
                ?: defaults.lockAfter,
            hideInRecents = prefs.getBoolean(KEY_HIDE_IN_RECENTS, defaults.hideInRecents),
        )
    }

    private companion object {
        const val KEY_ENABLED = "enabled"
        const val KEY_LOCK_AFTER = "lock_after"
        const val KEY_HIDE_IN_RECENTS = "hide_in_recents"
    }
}
