package dev.kortex.finance.domain.read

import java.time.LocalDate
import java.time.LocalTime
import java.time.Month
import java.time.format.TextStyle
import java.util.Locale

/** A value found in a text and where: [range] is what the screen underlines (Figma: Paste SMS 02). */
data class Found<T>(val value: T, val range: IntRange)

/** Amounts, dates and times as Indian bank SMS and receipts write them. */
object TextReading {

    /** "Rs.42.50", "INR 1,850.00", "₹5,420", "Rs 98,150". */
    val CurrencyAmount = Regex("""(?:Rs\.?|INR|₹)\s?([0-9][0-9,]*(?:\.[0-9]{1,2})?)""", RegexOption.IGNORE_CASE)

    /** A bare amount with paise, as receipts print it: "1,239.00". */
    val BareAmount = Regex("""(?<![\d.,])([0-9][0-9,]*\.[0-9]{2})(?![\d])""")

    /** "48,557.50" → 4855750 paise; null if it isn't a number. */
    fun minorOf(text: String): Long? {
        val clean = text.replace(",", "").trim()
        if (clean.isEmpty() || clean.count { it == '.' } > 1) return null
        val whole = clean.substringBefore('.').ifEmpty { "0" }
        val fraction = clean.substringAfter('.', "").padEnd(2, '0').take(2)
        if (!whole.all(Char::isDigit) || !fraction.all(Char::isDigit) || whole.length > 12) return null
        return whole.toLong() * 100 + fraction.toLong()
    }

    private val Numeric = Regex("""(?<!\d)(\d{1,4})[-/.](\d{1,2})[-/.](\d{2,4})(?!\d)""")
    private val Named = Regex(
        """(?<!\d)(\d{1,2})(?:st|nd|rd|th)?[\s-]?(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Sept|Oct|Nov|Dec)[a-z]*\.?(?:[\s,-]+'?(\d{2,4}))?(?!\d)""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * The first date in [text]: "28-09-26", "28/09/2026", "2026-09-28", "28 Sep", "28-Sep-26".
     * Day comes before month, as in India. A date without a year takes the one that keeps it on
     * or before [today].
     */
    fun date(text: String, today: LocalDate): Found<LocalDate>? {
        val candidates = buildList {
            Numeric.findAll(text).forEach { m ->
                val (a, b, c) = m.destructured
                val date = if (a.length == 4) {
                    runCatching { LocalDate.of(a.toInt(), b.toInt(), c.toInt()) }.getOrNull()
                } else {
                    runCatching { LocalDate.of(year(c), b.toInt(), a.toInt()) }.getOrNull()
                }
                date?.let { add(Found(it, m.range)) }
            }
            Named.findAll(text).forEach { m ->
                val day = m.groupValues[1].toInt()
                val month = monthOf(m.groupValues[2]) ?: return@forEach
                val explicit = m.groupValues[3].takeIf { it.isNotEmpty() }?.let(::year)
                val date = runCatching {
                    if (explicit != null) {
                        LocalDate.of(explicit, month, day)
                    } else {
                        LocalDate.of(today.year, month, day).let { if (it.isAfter(today)) it.minusYears(1) else it }
                    }
                }.getOrNull()
                date?.let { add(Found(it, m.range)) }
            }
        }
        return candidates.minByOrNull { it.range.first }
    }

    private val Clock = Regex("""(?<!\d)(\d{1,2}):(\d{2})(?::\d{2})?\s*([AaPp]\.?[Mm]\.?)?(?!\d)""")

    /** The first time in [text]: "18:42", "6:42 PM", "06:42:10 pm". */
    fun time(text: String): Found<LocalTime>? = Clock.findAll(text).firstNotNullOfOrNull { m ->
        var hour = m.groupValues[1].toInt()
        val minute = m.groupValues[2].toInt()
        val meridiem = m.groupValues[3].lowercase().replace(".", "")
        if (meridiem == "pm" && hour < 12) hour += 12
        if (meridiem == "am" && hour == 12) hour = 0
        runCatching { Found(LocalTime.of(hour, minute), m.range) }.getOrNull()
    }

    /**
     * What may be sent to an LLM: every run of five or more digits keeps only its last four, so
     * card, account and phone numbers never leave the phone whole (docs/FINANCE_PLAN.md › Decisions).
     */
    fun maskForModel(text: String): String = LongDigits.replace(text) { m -> "X".repeat(m.value.length - 4) + m.value.takeLast(4) }

    private val LongDigits = Regex("""\d{5,}""")

    private fun year(text: String): Int = text.toInt().let { if (text.length == 2) 2000 + it else it }

    private fun monthOf(text: String): Month? = Month.entries.firstOrNull {
        it.getDisplayName(TextStyle.SHORT, Locale.ENGLISH).equals(text.take(3), ignoreCase = true)
    }

    /** "WHOLE FOODS MARKET" → "Whole Foods Market"; company suffixes dropped ("ACME TECHNOLOGIES PVT LTD" → "Acme Technologies"). */
    fun tidyName(raw: String): String {
        val words = raw.trim().trimEnd('.', ',', ';', ':').split(Regex("""\s+""")).filter { it.isNotBlank() }
        val kept = words.dropLastWhile { it.trimEnd('.').uppercase() in CompanySuffixes }.ifEmpty { words }
        val shouting = kept.none { word -> word.any(Char::isLowerCase) }
        return kept.joinToString(" ") { word ->
            if (!shouting || word.length <= 2 && word.all(Char::isLetter) && word.uppercase() in KeepUpper) word
            else word.lowercase().replaceFirstChar { it.titlecase(Locale.ENGLISH) }
        }
    }

    private val CompanySuffixes = setOf("PVT", "PRIVATE", "LTD", "LIMITED", "LLP", "INC", "CO", "CORP")
    private val KeepUpper = setOf("UK", "US", "IN", "HP", "TV", "SBI")
}
