package dev.kortex.core.log

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * [atLeast] is the gate [AndroidLogger] puts in front of Logcat: release builds keep only
 * WARN/ERROR, since DEBUG/INFO lines carry user queries, tool payloads and LLM replies.
 */
class LoggerLevelGateTest {

    private val seen = mutableListOf<Logger.Level>()
    private val sink = Logger { level, _, _, _ -> seen += level }

    private fun Logger.logEveryLevel() = Logger.Level.entries.forEach { log(it, "Tag", "msg", null) }

    @Test
    fun `WARN gate drops DEBUG and INFO`() {
        sink.atLeast { Logger.Level.WARN }.logEveryLevel()

        seen shouldBe listOf(Logger.Level.WARN, Logger.Level.ERROR)
    }

    @Test
    fun `DEBUG gate forwards every level`() {
        sink.atLeast { Logger.Level.DEBUG }.logEveryLevel()

        seen shouldBe Logger.Level.entries
    }

    @Test
    fun `gate reads the minimum level on every call`() {
        var min = Logger.Level.ERROR
        val gated = sink.atLeast { min }

        gated.d("Tag", "dropped")
        min = Logger.Level.DEBUG
        gated.d("Tag", "kept")

        seen shouldBe listOf(Logger.Level.DEBUG)
    }

    @Test
    fun `gate forwards tag, message and error unchanged`() {
        var forwarded: Triple<String, String, Throwable?>? = null
        val error = IllegalStateException("boom")

        Logger { _, tag, message, err -> forwarded = Triple(tag, message, err) }
            .atLeast { Logger.Level.WARN }
            .e("Tag", "failed", error)

        forwarded shouldBe Triple("Tag", "failed", error)
    }

    @Test
    fun `AndroidLogger defaults to WARN`() {
        AndroidLogger.minLevel shouldBe Logger.Level.WARN
    }
}
