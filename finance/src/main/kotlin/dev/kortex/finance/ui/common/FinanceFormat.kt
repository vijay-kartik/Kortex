package dev.kortex.finance.ui.common

import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

/**
 * How money and dates read on the Finance screens: rupees with Indian digit grouping (₹3,84,200.00),
 * and short English dates (Thu, 15 Oct). Calculators work in paise; only this rounds.
 */
object FinanceFormat {

    /** ₹24,850.12; without [paise] rounded to the rupee (₹4,467); [signed] adds + to gains. */
    fun rupees(minor: Long, paise: Boolean = true, signed: Boolean = false): String {
        val magnitude = abs(minor)
        val body = if (paise) "${groupIndian(magnitude / 100)}.${"%02d".format(magnitude % 100)}" else groupIndian((magnitude + 50) / 100)
        val sign = when {
            minor < 0 -> "-"
            signed && minor > 0 -> "+"
            else -> ""
        }
        return "$sign₹$body"
    }

    /** 384200 → 3,84,200: the last three digits, then pairs. */
    fun groupIndian(value: Long): String {
        val digits = value.toString()
        if (digits.length <= 3) return digits
        val head = digits.dropLast(3).reversed().chunked(2).joinToString(",").reversed()
        return "$head,${digits.takeLast(3)}"
    }

    /**
     * Reads a typed amount into paise: digits with at most one point and two decimals; commas and
     * spaces are ignored. Null when blank, malformed or not above zero.
     */
    fun parseAmount(text: String): Long? {
        val cleaned = text.filterNot { it == ',' || it.isWhitespace() || it == '₹' }
        if (cleaned.isEmpty() || cleaned.count { it == '.' } > 1 || cleaned.any { !it.isDigit() && it != '.' }) return null
        val whole = cleaned.substringBefore('.').ifEmpty { "0" }
        val fraction = cleaned.substringAfter('.', "")
        if (fraction.length > 2 || whole.length > 12) return null
        val minor = whole.toLong() * 100 + fraction.padEnd(2, '0').toLong()
        return minor.takeIf { it > 0 }
    }

    /** The amount as it goes back into an input field: 1239.5 for ₹1,239.50, 1239 for ₹1,239.00. */
    fun amountInput(minor: Long): String =
        if (minor % 100 == 0L) (minor / 100).toString() else "${minor / 100}.${"%02d".format(minor % 100)}".trimEnd('0')

    /** 29 Sep */
    fun day(date: LocalDate): String = date.format(DAY)

    /** Thu, 15 Oct */
    fun weekdayDay(date: LocalDate): String = date.format(WEEKDAY_DAY)

    /** 28 Sep 2026 */
    fun fullDate(date: LocalDate): String = date.format(FULL)

    /** September 2026 */
    fun monthYear(month: YearMonth): String = month.format(MONTH_YEAR)

    /** September */
    fun monthName(month: YearMonth): String = month.format(MONTH)

    /** SEP */
    fun monthShort(month: YearMonth): String = month.format(MONTH_SHORT).uppercase(Locale.ENGLISH)

    /** 12:34 PM */
    fun time(millis: Long, zone: ZoneId): String = Instant.ofEpochMilli(millis).atZone(zone).format(TIME)

    /** Today, Yesterday, or 29 Sep. */
    fun relativeDay(date: LocalDate, today: LocalDate): String = when (date) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> day(date)
    }

    /** 1st, 2nd, 3rd, 11th, 25th. */
    fun ordinal(day: Int): String {
        val suffix = if (day % 100 in 11..13) "th" else when (day % 10) {
            1 -> "st"
            2 -> "nd"
            3 -> "rd"
            else -> "th"
        }
        return "$day$suffix"
    }

    private val DAY = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)
    private val WEEKDAY_DAY = DateTimeFormatter.ofPattern("EEE, d MMM", Locale.ENGLISH)
    private val FULL = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)
    private val MONTH_YEAR = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH)
    private val MONTH = DateTimeFormatter.ofPattern("MMMM", Locale.ENGLISH)
    private val MONTH_SHORT = DateTimeFormatter.ofPattern("MMM", Locale.ENGLISH)
    private val TIME = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)
}
