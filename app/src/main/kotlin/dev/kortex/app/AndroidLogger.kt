package dev.kortex.app

import android.util.Log
import dev.kortex.core.log.Logger

/**
 * Routes core-agent's [Logger] calls to Logcat under a common "Kortex." tag prefix, so
 * `adb logcat -s Kortex.ReActNode,Kortex.ToolGovernor,Kortex.OpenAiProvider,...` (or just
 * grepping for "Kortex.") shows what the agent, LLM calls, and builtin tools are doing.
 */
object AndroidLogger : Logger {
    private const val PREFIX = "Kortex"

    override fun log(level: Logger.Level, tag: String, message: String, error: Throwable?) {
        val fullTag = "$PREFIX.$tag"
        when (level) {
            Logger.Level.DEBUG -> Log.d(fullTag, message, error)
            Logger.Level.INFO -> Log.i(fullTag, message, error)
            Logger.Level.WARN -> Log.w(fullTag, message, error)
            Logger.Level.ERROR -> Log.e(fullTag, message, error)
        }
    }
}
