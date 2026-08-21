package dev.kortex.wa

/**
 * The logcat tag used across the module, in one place so a host can rebrand it and still get a
 * single greppable tag for the whole stack.
 *
 * A plain holder rather than a constructor parameter: the protocol layer logs from deep inside
 * the read loop and the Signal store, and threading a tag through every one of those call sites
 * would be far more churn than the setting is worth. Set once from
 * `WhatsAppManager.Config.logTag` when the session starts.
 */
internal object WaLog {
    @Volatile
    var tag: String = "WhatsApp"
}
