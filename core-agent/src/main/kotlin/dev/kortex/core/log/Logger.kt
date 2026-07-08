package dev.kortex.core.log

/**
 * Structured logging hook so a host (Logcat, console, file) can observe what the agent
 * is doing — LLM requests/responses, tool calls, and governor decisions. Kept as a plain
 * Kotlin interface (no android.util.Log dependency) so core-agent's JVM unit tests don't
 * need Robolectric; the app module wires in a Logcat-backed implementation.
 */
fun interface Logger {
    fun log(level: Level, tag: String, message: String, error: Throwable?)

    enum class Level { DEBUG, INFO, WARN, ERROR }

    companion object {
        /** Discards everything. */
        val NONE = Logger { _, _, _, _ -> }

        /** Prints to stdout — the default, so logs are visible even without host wiring. */
        val CONSOLE = Logger { level, tag, message, error ->
            println("[$level] $tag: $message")
            error?.printStackTrace()
        }
    }
}

fun Logger.d(tag: String, message: String) = log(Logger.Level.DEBUG, tag, message, null)
fun Logger.i(tag: String, message: String) = log(Logger.Level.INFO, tag, message, null)
fun Logger.w(tag: String, message: String, error: Throwable? = null) = log(Logger.Level.WARN, tag, message, error)
fun Logger.e(tag: String, message: String, error: Throwable? = null) = log(Logger.Level.ERROR, tag, message, error)
