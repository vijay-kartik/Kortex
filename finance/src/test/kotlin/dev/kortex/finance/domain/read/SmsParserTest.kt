package dev.kortex.finance.domain.read

import dev.kortex.finance.Fixtures.TODAY
import java.time.LocalDate
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The messages on Figma's Paste SMS screens. */
class SmsParserTest {
    private val hdfcCard = "Rs.42.50 spent on HDFC Bank Card x8824 at WHOLE FOODS MARKET on 28-09-26. Avl Lmt: Rs.48,557.50. Not you? Call your bank."
    private val iciciCard = "Rs.1,850.00 spent on ICICI Bank Card XX5512 at DECATHLON SPORTS on 30-09-26. Avl Lmt: Rs.98,150.00"
    private val neft = "Rs.5,420.00 credited to A/c XX4471 on 01-10-26 by NEFT from ACME TECHNOLOGIES PVT LTD. Avl Bal: Rs.17,870.00"
    private val upi = "Sent Rs.250.00 from A/c XX4471 to paytmqr2810050@paytm on 29-09-26. UPI Ref: 427381920011. Not you? Call your bank."

    @Test
    fun `a card spend`() {
        val sms = SmsParser.parse(hdfcCard, TODAY)!!
        assertEquals(MoneyDirection.DEBIT, sms.direction)
        assertEquals(42_50L, sms.amountMinor)
        assertEquals("8824", sms.last4)
        assertEquals(Instrument.CARD, sms.instrument)
        assertEquals(LocalDate.of(2026, 9, 28), sms.date)
        assertEquals("WHOLE FOODS MARKET", sms.payee)
        assertFalse(sms.payeeIsUpiId)
        assertEquals(48_557_50L, sms.availableLimitMinor)
        assertNull(sms.availableBalanceMinor)
        assertEquals("HDFC Bank", sms.bank)
        assertTrue(sms.isCreditCard)
        assertEquals("Rs.42.50", hdfcCard.substring(sms.spans.getValue(SmsField.AMOUNT)))
        assertEquals("WHOLE FOODS MARKET", hdfcCard.substring(sms.spans.getValue(SmsField.PAYEE)))
        assertEquals("x8824", hdfcCard.substring(sms.spans.getValue(SmsField.LAST4)))
        assertEquals("28-09-26", hdfcCard.substring(sms.spans.getValue(SmsField.DATE)))
        assertEquals("48,557.50", hdfcCard.substring(sms.spans.getValue(SmsField.LIMIT)))
    }

    @Test
    fun `a card the phone doesn't know`() {
        val sms = SmsParser.parse(iciciCard, TODAY)!!
        assertEquals(1_850_00L, sms.amountMinor)
        assertEquals("5512", sms.last4)
        assertEquals("DECATHLON SPORTS", sms.payee)
        assertEquals(98_150_00L, sms.availableLimitMinor)
        assertEquals("ICICI Bank", sms.bank)
    }

    @Test
    fun `money in is income`() {
        val sms = SmsParser.parse(neft, TODAY)!!
        assertEquals(MoneyDirection.CREDIT, sms.direction)
        assertEquals(5_420_00L, sms.amountMinor)
        assertEquals("4471", sms.last4)
        assertEquals(Instrument.ACCOUNT, sms.instrument)
        assertEquals("ACME TECHNOLOGIES PVT LTD", sms.payee)
        assertEquals(17_870_00L, sms.availableBalanceMinor)
        assertFalse(sms.cardPaymentReceived)
        assertEquals("Acme Technologies", TextReading.tidyName(sms.payee!!))
    }

    @Test
    fun `a UPI payment names only an id and carries a reference`() {
        val sms = SmsParser.parse(upi, TODAY)!!
        assertEquals(MoneyDirection.DEBIT, sms.direction)
        assertEquals(250_00L, sms.amountMinor)
        assertEquals("4471", sms.last4)
        assertEquals(Instrument.ACCOUNT, sms.instrument)
        assertEquals("paytmqr2810050@paytm", sms.payee)
        assertTrue(sms.payeeIsUpiId)
        assertEquals("427381920011", sms.ref)
        assertEquals(LocalDate.of(2026, 9, 29), sms.date)
    }

    @Test
    fun `a card bill payment received is a card payment`() {
        val sms = SmsParser.parse("Payment of Rs.1,400.00 has been received on your ICICI Bank Credit Card XX5512 on 05-10-26. Thank you.", TODAY)!!
        assertEquals(MoneyDirection.CREDIT, sms.direction)
        assertEquals(Instrument.CARD, sms.instrument)
        assertTrue(sms.cardPaymentReceived)
    }

    @Test
    fun `times and month names are read too`() {
        val sms = SmsParser.parse("INR 2,000.00 debited from a/c **4471 on 12-Sep-26 at 06:42 PM. Info: AMAZON PAY. Avl Bal INR 10,000.00", TODAY)!!
        assertEquals(2_000_00L, sms.amountMinor)
        assertEquals(LocalDate.of(2026, 9, 12), sms.date)
        assertEquals(LocalTime.of(18, 42), sms.time)
        assertEquals("4471", sms.last4)
        assertEquals(10_000_00L, sms.availableBalanceMinor)
        assertEquals("AMAZON PAY", sms.payee)
    }

    @Test
    fun `OTPs and offers aren't payments`() {
        assertEquals(SmsKind.OTP, SmsParser.classify("482913 is your OTP for a txn of Rs.1,200.00 at AMAZON on HDFC Bank Card x8824. Do not share it.", TODAY))
        assertEquals(SmsKind.PROMO, SmsParser.classify("Congratulations! You are pre-approved for a personal loan. Apply now.", TODAY))
        assertEquals(SmsKind.UNREADABLE, SmsParser.classify("Your statement for September is ready.", TODAY))
        assertEquals(SmsKind.TRANSACTION, SmsParser.classify(hdfcCard, TODAY))
    }

    @Test
    fun `numbers the model sees keep only their last four digits`() {
        assertEquals("A/c XXXXXXXX4471 Ref XXXXXXXX0011 Rs.1,850.00", TextReading.maskForModel("A/c 123456784471 Ref 427381920011 Rs.1,850.00"))
    }
}
