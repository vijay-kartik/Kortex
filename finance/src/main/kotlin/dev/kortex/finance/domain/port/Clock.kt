package dev.kortex.finance.domain.port

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Wall-clock time and zone, so use cases can be tested at a fixed instant. */
interface Clock {
    fun nowMillis(): Long

    /** The zone a transaction's day is taken in when it's saved. */
    fun zone(): ZoneId

    fun today(): LocalDate = dayOf(nowMillis())

    fun dayOf(millis: Long): LocalDate = Instant.ofEpochMilli(millis).atZone(zone()).toLocalDate()

    companion object {
        val System: Clock = object : Clock {
            override fun nowMillis() = java.lang.System.currentTimeMillis()
            override fun zone(): ZoneId = ZoneId.systemDefault()
        }
    }
}
