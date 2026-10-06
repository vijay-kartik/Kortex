package dev.kortex.finance.ui

import dev.kortex.finance.Fixtures
import dev.kortex.finance.Fixtures.IST
import dev.kortex.finance.Fixtures.TODAY
import dev.kortex.finance.domain.model.InboxSms
import dev.kortex.finance.domain.model.InboxStatus
import dev.kortex.finance.domain.model.ReviewReason
import dev.kortex.finance.domain.read.MoneyDirection
import dev.kortex.finance.domain.read.ParsedSms
import dev.kortex.finance.domain.usecase.AutoSaved
import dev.kortex.finance.domain.usecase.MerchantGuess
import dev.kortex.finance.domain.usecase.SmsEntryPlan
import dev.kortex.finance.domain.usecase.SmsEntryType
import dev.kortex.finance.sms.BankSmsNotifications
import dev.kortex.finance.ui.read.SmsReviewUi
import org.junit.Assert.assertEquals
import org.junit.Test

class SmsReviewUiTest {
    private fun at(day: java.time.LocalDate, hour: Int, minute: Int) = day.atTime(hour, minute).atZone(IST).toInstant().toEpochMilli()

    @Test
    fun `rows show who sent it, why it's waiting and when it came`() {
        val rows = listOf(
            InboxSms("a", "AX-HDFCBK", "INR 420.00 debited from a/c **9999", at(TODAY, 18, 42), InboxStatus.REVIEW, ReviewReason.NO_ACCOUNT),
            InboxSms("b", "VM-ICICIT", "Payment of Rs.1,400.00 received", at(TODAY.minusDays(1), 9, 5), InboxStatus.REVIEW, ReviewReason.CARD),
            // Handled meanwhile: no text left to show.
            InboxSms("c", "AX-HDFCBK", null, at(TODAY, 8, 0), InboxStatus.REVIEW, ReviewReason.LATE),
        )
        val state = SmsReviewUi.build(rows, TODAY, IST)
        assertEquals(listOf("a", "b"), state.rows.map { it.id })
        assertEquals("Which account?", state.rows[0].reason)
        assertEquals("Today, 6:42 PM", state.rows[0].received)
        assertEquals("Yesterday, 9:05 AM", state.rows[1].received)
        assertEquals("Card payment or refund", state.rows[1].reason)
    }

    @Test
    fun `every reason has words`() {
        ReviewReason.entries.forEach { assertEquals(false, SmsReviewUi.reasonLabel(it).isBlank()) }
    }

    @Test
    fun `a saved entry's notification names the amount, the merchant, the account and the category`() {
        fun saved(type: SmsEntryType, merchant: String?, category: String?) = AutoSaved(
            smsId = "s",
            transactionUid = "t",
            plan = SmsEntryPlan(
                sms = ParsedSms(MoneyDirection.DEBIT, 420_00, last4 = "4471", payee = "SWIGGY"),
                type = type,
                account = Fixtures.checking,
                merchant = MerchantGuess(merchant, null, null),
                date = TODAY,
                occurredAtMillis = 0,
                uid = "t",
                duplicate = null,
            ),
            categoryName = category,
        )
        assertEquals("Saved ₹420.00 at Swiggy", BankSmsNotifications.title(saved(SmsEntryType.EXPENSE, "Swiggy", "Food")))
        assertEquals("Saved ₹420.00 from Swiggy", BankSmsNotifications.title(saved(SmsEntryType.INCOME, "Swiggy", null)))
        assertEquals("Saved ₹420.00 at SWIGGY", BankSmsNotifications.title(saved(SmsEntryType.EXPENSE, null, null)))
        assertEquals("Checking Account ••4471 · Food", BankSmsNotifications.text(saved(SmsEntryType.EXPENSE, "Swiggy", "Food")))
        assertEquals("Checking Account ••4471 · No category", BankSmsNotifications.text(saved(SmsEntryType.EXPENSE, "Swiggy", null)))
        assertEquals("3 bank SMS to review", BankSmsNotifications.reviewTitle(3))
    }
}
