package dev.kortex.finance.sms

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.updateAndGet

/** Settings › FINANCES (docs/SMS_AUTO_PLAN.md › Decisions). */
data class BankSmsSettings(
    /** "Add bank SMS automatically": received SMS are read at all. Off by default. */
    val enabled: Boolean = false,
    /** "Save clear ones without asking"; off, everything readable waits in review. */
    val autoSave: Boolean = true,
)

/**
 * Bank SMS settings, on this phone only. SharedPreferences rather than DataStore because the SMS
 * receiver has seconds to decide and reads them on the main thread.
 */
class BankSmsStore(context: Context) {

    private val prefs = context.getSharedPreferences("bank_sms", Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<BankSmsSettings> = _settings.asStateFlow()

    fun update(transform: (BankSmsSettings) -> BankSmsSettings) {
        val next = _settings.updateAndGet(transform)
        prefs.edit()
            .putBoolean(KEY_ENABLED, next.enabled)
            .putBoolean(KEY_AUTO_SAVE, next.autoSave)
            .apply()
    }

    private fun load(): BankSmsSettings {
        val defaults = BankSmsSettings()
        return BankSmsSettings(
            enabled = prefs.getBoolean(KEY_ENABLED, defaults.enabled),
            autoSave = prefs.getBoolean(KEY_AUTO_SAVE, defaults.autoSave),
        )
    }

    private companion object {
        const val KEY_ENABLED = "enabled"
        const val KEY_AUTO_SAVE = "auto_save"
    }
}
