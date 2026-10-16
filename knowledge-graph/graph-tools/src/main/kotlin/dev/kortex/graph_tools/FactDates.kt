package dev.kortex.graph_tools

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Date parsing and formatting for extracted facts. Zone-less inputs ("2025-07-16",
 * "16-Jul-2025 15:00") are read in [zone], and [iso] renders back in it.
 */
internal class FactDates(private val zone: ZoneId) {

    fun iso(millis: Long): String =
        Instant.ofEpochMilli(millis).atZone(zone).format(ISO_OUT)

    /** For values in date fields: epoch millis or any recognized date format. */
    fun parseExplicitTimestamp(text: String): Long {
        text.toLongOrNull()
            ?.takeIf { it in EPOCH_MILLIS_MIN..EPOCH_MILLIS_MAX }
            ?.let { return it }
        return parseDateLike(text)
    }

    /**
     * For inspecting values that should NOT be dates. Deliberately excludes
     * bare numbers: booking references and phone numbers are not timestamps,
     * however numeric they look.
     */
    fun parseDateLike(text: String): Long {
        runCatching { Instant.parse(text) }.getOrNull()?.let { return it.toEpochMilli() }
        runCatching { OffsetDateTime.parse(text) }.getOrNull()
            ?.let { return it.toInstant().toEpochMilli() }

        for (format in DATE_TIME_FORMATS) {
            runCatching { LocalDateTime.parse(text, format) }.getOrNull()
                ?.let { return it.atZone(zone).toInstant().toEpochMilli() }
        }
        for (format in DATE_FORMATS) {
            runCatching { LocalDate.parse(text, format) }.getOrNull()
                ?.let { return it.atStartOfDay(zone).toInstant().toEpochMilli() }
        }
        return 0L
    }

    private companion object {
        /**
         * Plausible range for a bare number to be epoch millis: 1973 to 2100.
         * Without a floor, a phone number like "01146462000" parses as a Long and
         * would be mistaken for a timestamp.
         */
        private const val EPOCH_MILLIS_MIN = 100_000_000_000L
        private const val EPOCH_MILLIS_MAX = 4_102_444_800_000L

        private val DATE_TIME_FORMATS: List<DateTimeFormatter> = listOf(
            DateTimeFormatter.ISO_LOCAL_DATE_TIME,
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm[:ss]", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("d-MMM-yyyy HH:mm", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("d-MMM-yyyy, HH:mm", Locale.ENGLISH),
        )

        private val DATE_FORMATS: List<DateTimeFormatter> = listOf(
            DateTimeFormatter.ISO_LOCAL_DATE,
            DateTimeFormatter.ofPattern("d-MMM-yyyy", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH),
        )

        private val ISO_OUT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm", Locale.ENGLISH)
    }
}
