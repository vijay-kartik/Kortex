package dev.kortex.myinfo.topics.domain.usecase

import dev.kortex.myinfo.topics.domain.model.Money
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Currency
import java.util.Locale

/** Reads what the bill form's amount field holds (Figma: Topics 1d, bill). */
object MoneyAmount {

    /**
     * Parses a typed amount into [currency]'s minor unit. Digits, spaces and both separators are
     * accepted: the last `.` or `,` is the decimal point unless three digits follow it and the
     * currency doesn't take three, in which case every separator is grouping — so "4,280.50",
     * "4.280,50" and "4280.5" all read as 4280.50, and "4,280" as 4280. More decimals than the
     * currency takes are rounded away.
     *
     * @return null for a blank, unparseable, negative or absurdly large amount.
     */
    fun parse(text: String, currency: String): Money? {
        val digits = fractionDigits(currency)
        val cleaned = text.filterNot { it.isWhitespace() }
        if (cleaned.isEmpty() || cleaned.any { it !in AcceptedChars }) return null

        val separator = cleaned.indexOfLast { it == '.' || it == ',' }
        val decimals = if (separator < 0) 0 else cleaned.length - separator - 1
        // "1,000" is a thousand, not one; but a three-decimal currency really does take "1.234".
        val grouping = separator < 0 || (decimals == 3 && digits != 3)
        val normalised = if (grouping) {
            cleaned.filter { it.isDigit() }
        } else {
            cleaned.take(separator).filter { it.isDigit() } + "." + cleaned.substring(separator + 1)
        }
        if (normalised.none { it.isDigit() }) return null

        return runCatching {
            val amount = BigDecimal(normalised).setScale(digits, RoundingMode.HALF_UP)
            Money(amount.movePointRight(digits).longValueExact(), currency)
        }.getOrNull()
    }

    /** The currency the amount field starts on: the one this device's region uses, else USD. */
    fun defaultCurrency(locale: Locale = Locale.getDefault()): String =
        runCatching { Currency.getInstance(locale).currencyCode }.getOrDefault(FALLBACK_CURRENCY)

    /** How many decimals [currency] takes; two for a code this device doesn't know. */
    fun fractionDigits(currency: String): Int =
        runCatching { Currency.getInstance(currency).defaultFractionDigits }.getOrDefault(2).coerceIn(0, 4)

    /** An ISO 4217 code is three letters; anything else can't be a currency. */
    fun isCurrencyCode(text: String): Boolean = text.length == 3 && text.all { it in 'A'..'Z' }

    private const val FALLBACK_CURRENCY = "USD"
    private const val AcceptedChars = "0123456789.,"
}
