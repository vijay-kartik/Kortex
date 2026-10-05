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

    /**
     * When something picked as happening on [day] is saved: now for today, otherwise noon, so the
     * day can't slip across zones.
     */
    fun millisOn(day: LocalDate): Long =
        if (day == today()) nowMillis() else day.atTime(12, 0).atZone(zone()).toInstant().toEpochMilli()

    companion object {
        val System: Clock = object : Clock {
            override fun nowMillis() = java.lang.System.currentTimeMillis()
            override fun zone(): ZoneId = ZoneId.systemDefault()
        }
    }
}
