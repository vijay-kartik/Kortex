package dev.kortex.finance.domain.read

import dev.kortex.finance.Fixtures.TODAY
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The receipt on Figma's Scan receipt screens. */
class ReceiptParserTest {
    private val brewhouse = """
        BREWHOUSE CAFÉ
        100 Ft Rd, Indiranagar
        GSTIN 29ABCDE1234F1Z5
        30/09/2026 13:42      Bill #4821
        Cappuccino x2          420.00
        Avocado toast          380.00
        Banana bread           180.00
        Cold brew              200.00
        Subtotal             1,180.00
        CGST 2.5%               29.50
        SGST 2.5%               29.50
        TOTAL                1,239.00
        PAID VISA ****8824   1,239.00
        Thank you · visit again
    """.trimIndent()

    @Test
    fun `a clear receipt`() {
        val receipt = ReceiptParser.parse(brewhouse, TODAY)
        assertEquals("Brewhouse Café", receipt.merchant)
        assertEquals(LocalDate.of(2026, 9, 30), receipt.date)
        assertEquals(1_239_00L, receipt.total)
        assertEquals(59_00L, receipt.taxMinor)
        assertEquals("8824", receipt.last4)
        assertEquals(listOf("Cappuccino", "Avocado toast", "Banana bread", "Cold brew"), receipt.items.map { it.name })
        assertEquals(2, receipt.items.first().quantity)
        assertEquals(listOf(1_239_00L, 1_180_00L), receipt.candidates.map { it.amountMinor })
        assertEquals("Total, incl. ₹59 GST", receipt.candidates.first().label)
    }

    @Test
    fun `a smudged total leaves the choice to the person`() {
        val smudged = brewhouse.lines().filterNot { it.startsWith("TOTAL") || it.startsWith("PAID") }.joinToString("\n")
        val receipt = ReceiptParser.parse(smudged, TODAY)
        assertNull(receipt.total)
        assertEquals(listOf(1_239_00L, 1_180_00L), receipt.candidates.map { it.amountMinor })
    }

    @Test
    fun `nothing that looks like money is unreadable`() {
        assertTrue(ReceiptParser.parse("BREWHOUSE\n~~ ~~~ ~~\n", TODAY).unreadable)
        assertTrue(ReceiptParser.parse("", TODAY).unreadable)
    }
}
