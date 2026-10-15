package dev.kortex.core.log

import android.util.Log

/**
 * Routes core-agent's [Logger] calls to Logcat under a common "Kortex." tag prefix, so
 * `adb logcat -s Kortex.ReActNode,Kortex.ToolGovernor,Kortex.OpenAiProvider,...` (or just
 * grepping for "Kortex.") shows what the agent, LLM calls, and builtin tools are doing.
 *
 * Lines below [minLevel] are dropped. It defaults to WARN because DEBUG/INFO lines carry the
 * user's queries, tool arguments/results and LLM replies; the host lowers it to DEBUG only in
 * debuggable builds. In-app consumers (reasoning panel, Runs screen) get every line through
 * their own loggers, not through this one.
 */
object AndroidLogger : Logger {
    private const val PREFIX = "Kortex"

    @Volatile
    var minLevel: Logger.Level = Logger.Level.WARN

    private val logcat = Logger { level, tag, message, error ->
        val fullTag = "$PREFIX.$tag"
        when (level) {
            Logger.Level.DEBUG -> Log.d(fullTag, message, error)
            Logger.Level.INFO -> Log.i(fullTag, message, error)
            Logger.Level.WARN -> Log.w(fullTag, message, error)
            Logger.Level.ERROR -> Log.e(fullTag, message, error)
        }
    }.atLeast { minLevel }

    override fun log(level: Logger.Level, tag: String, message: String, error: Throwable?) =
        logcat.log(level, tag, message, error)
}
