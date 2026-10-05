package dev.kortex.finance.domain

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FinanceIdsTest {
    private val sms = "Rs.42.50 spent on HDFC Bank Card x8824 at WHOLE FOODS MARKET on 28-09-26."

    @Test
    fun `the same SMS gives the same id wherever it's pasted`() {
        val id = FinanceIds.smsTransaction("VM-HDFCBK", sms)
        assertEquals(id, FinanceIds.smsTransaction(" vm-hdfcbk ", "  $sms\n"))
        assertTrue(id.startsWith("sms_"))
        assertEquals(36, id.length)
        assertNotEquals(id, FinanceIds.smsTransaction("VM-HDFCBK", sms.replace("42.50", "42.51")))
    }

    @Test
    fun `one occurrence of a recurring payment has one id`() {
        val oct = FinanceIds.recurringOccurrence("netflix", LocalDate.of(2026, 10, 3))
        assertEquals(oct, FinanceIds.recurringOccurrence("netflix", LocalDate.of(2026, 10, 3)))
        assertNotEquals(oct, FinanceIds.recurringOccurrence("netflix", LocalDate.of(2026, 11, 3)))
        assertTrue(oct.startsWith("rec_"))
    }

    @Test
    fun `statements and merchants are keyed by what they are`() {
        assertEquals(FinanceIds.statement("kortex", LocalDate.of(2026, 9, 25)), FinanceIds.statement("kortex", LocalDate.of(2026, 9, 25)))
        assertEquals(FinanceIds.merchant("WHOLE FOODS  MARKET"), FinanceIds.merchant("Whole Foods Market"))
    }

    @Test
    fun `payee keys ignore case and spacing`() {
        assertEquals("whole foods market", FinanceIds.payeeKey("  WHOLE FOODS \t MARKET "))
        assertEquals("paytmqr2810050@paytm", FinanceIds.payeeKey("PaytmQR2810050@paytm"))
    }
}
