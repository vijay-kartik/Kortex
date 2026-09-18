package dev.kortex.myinfo.topics.domain

import dev.kortex.myinfo.topics.domain.model.Money
import dev.kortex.myinfo.topics.domain.usecase.MoneyAmount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MoneyAmountTest {

    @Test
    fun `both separators are read, whichever way round they are typed`() {
        assertEquals(Money(428_050, "AED"), MoneyAmount.parse("4,280.50", "AED"))
        assertEquals(Money(428_050, "AED"), MoneyAmount.parse("4.280,50", "AED"))
        assertEquals(Money(428_050, "AED"), MoneyAmount.parse("4280.5", "AED"))
        assertEquals(Money(428_000, "AED"), MoneyAmount.parse("4 280", "AED"))
    }

    @Test
    fun `three digits after the last separator are a thousand, not a fraction`() {
        assertEquals(Money(100_000, "USD"), MoneyAmount.parse("1,000", "USD"))
        assertEquals(Money(123_400, "USD"), MoneyAmount.parse("1.234", "USD"))
    }

    @Test
    fun `a currency with three decimals really does take three`() {
        assertEquals(Money(1_234, "KWD"), MoneyAmount.parse("1.234", "KWD"))
    }

    @Test
    fun `a currency with no minor unit keeps whole numbers`() {
        assertEquals(Money(1_200, "JPY"), MoneyAmount.parse("1,200", "JPY"))
        // Rounded to what the currency can hold rather than refused.
        assertEquals(Money(13, "JPY"), MoneyAmount.parse("12.5", "JPY"))
    }

    @Test
    fun `extra decimals round to what the currency takes`() {
        assertEquals(Money(1_239, "USD"), MoneyAmount.parse("12.3856", "USD"))
    }

    @Test
    fun `an unknown currency code is treated as a two-decimal one`() {
        assertEquals(Money(1_250, "ZZZ"), MoneyAmount.parse("12.50", "ZZZ"))
        assertEquals(2, MoneyAmount.fractionDigits("ZZZ"))
    }

    @Test
    fun `anything that isn't a plain positive number is refused`() {
        assertNull(MoneyAmount.parse("", "USD"))
        assertNull(MoneyAmount.parse("   ", "USD"))
        assertNull(MoneyAmount.parse(".", "USD"))
        assertNull(MoneyAmount.parse("about eighty", "USD"))
        assertNull(MoneyAmount.parse("-12.50", "USD"))
        assertNull(MoneyAmount.parse("12.50 USD", "USD"))
        assertNull(MoneyAmount.parse("99999999999999999999", "USD"))
    }

    @Test
    fun `currency codes are three capitals`() {
        assertTrue(MoneyAmount.isCurrencyCode("AED"))
        assertFalse(MoneyAmount.isCurrencyCode("aed"))
        assertFalse(MoneyAmount.isCurrencyCode("AE"))
        assertFalse(MoneyAmount.isCurrencyCode("AED1"))
    }
}
