package dev.kortex.finance.ui

import dev.kortex.finance.ui.common.FinanceFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FinanceFormatTest {
    @Test
    fun rupeesGroupsTheIndianWay() {
        assertEquals("₹3,84,200.00", FinanceFormat.rupees(3_84_200_00))
        assertEquals("₹999.50", FinanceFormat.rupees(999_50))
        assertEquals("₹1,00,00,000", FinanceFormat.rupees(1_00_00_000_00, paise = false))
        assertEquals("-₹42.50", FinanceFormat.rupees(-42_50))
        assertEquals("+₹42.50", FinanceFormat.rupees(42_50, signed = true))
        assertEquals("₹0.00", FinanceFormat.rupees(0, signed = true))
    }

    @Test
    fun rupeesWithoutPaiseRoundsHalfUp() {
        assertEquals("₹43", FinanceFormat.rupees(42_50, paise = false))
        assertEquals("₹42", FinanceFormat.rupees(42_49, paise = false))
    }

    @Test
    fun parseAmountReadsWhatPeopleType() {
        assertEquals(42_50L, FinanceFormat.parseAmount("42.5"))
        assertEquals(1_23_456_00L, FinanceFormat.parseAmount("₹1,23,456"))
        assertEquals(5L, FinanceFormat.parseAmount(".05"))
        assertEquals(10_00L, FinanceFormat.parseAmount(" 10. "))
    }

    @Test
    fun parseAmountRejectsNonsenseAndZero() {
        assertNull(FinanceFormat.parseAmount(""))
        assertNull(FinanceFormat.parseAmount("0"))
        assertNull(FinanceFormat.parseAmount("1.2.3"))
        assertNull(FinanceFormat.parseAmount("1.234"))
        assertNull(FinanceFormat.parseAmount("12a"))
        assertNull(FinanceFormat.parseAmount("-5"))
    }

    @Test
    fun amountInputRoundTrips() {
        assertEquals("1239", FinanceFormat.amountInput(1_239_00))
        assertEquals("1239.5", FinanceFormat.amountInput(1_239_50))
        assertEquals("1239.05", FinanceFormat.amountInput(1_239_05))
        listOf(1L, 99L, 1_239_50L, 50_000_00L).forEach { assertEquals(it, FinanceFormat.parseAmount(FinanceFormat.amountInput(it))) }
    }
}
