package dev.kortex.finance.ui

import dev.kortex.finance.domain.model.AccountKind
import dev.kortex.finance.domain.model.CategoryKind
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class FinanceRouteTest {
    private val routes = listOf(
        FinanceRoute.AddEntry(income = false),
        FinanceRoute.AddEntry(income = true),
        FinanceRoute.AddAccount(AccountKind.CREDIT_CARD),
        FinanceRoute.EditAccount("3f2a9c"),
        FinanceRoute.MonthlyReport(YearMonth.of(2026, 9)),
        FinanceRoute.Categories,
        FinanceRoute.CategoryForm(),
        FinanceRoute.CategoryForm(uid = "c1", kind = CategoryKind.INCOME),
        FinanceRoute.Pending,
        FinanceRoute.Recurring,
        FinanceRoute.RecurringForm(),
        FinanceRoute.RecurringForm("r1"),
        FinanceRoute.MarkPaid("netflix", java.time.LocalDate.of(2026, 10, 3)),
        FinanceRoute.PayBill("stmt_abc"),
        FinanceRoute.PasteSms(),
        FinanceRoute.PasteSms("Rs.42.50 spent on Card x8824 at A|B: on 28-09-26. Avl Lmt: Rs.48,557.50 ₹"),
        FinanceRoute.PasteSms("INR 420.00 debited from a/c **4471", inboxId = "sms_0f3a"),
        FinanceRoute.SmsReview,
        FinanceRoute.ScanReceipt,
        FinanceRoute.AddAccount(AccountKind.CREDIT_CARD, AccountPrefill("ICICI ••5512", "ICICI Bank", "5512", 98_150_00)),
        FinanceRoute.AddAccount(AccountKind.BANK, AccountPrefill(last4 = "4471")),
    )

    @Test
    fun everyRouteSurvivesEncodeAndDecode() {
        routes.forEach { assertEquals(it, FinanceRoute.decode(it.encode())) }
    }

    @Test
    fun encodedRoutesNeverHoldTheStackSeparator() {
        routes.forEach { assertFalse(it.encode().contains('|')) }
    }

    @Test
    fun unknownOrBrokenTextDecodesToNull() {
        assertNull(FinanceRoute.decode(""))
        assertNull(FinanceRoute.decode("nope"))
        assertNull(FinanceRoute.decode("report:September"))
        assertNull(FinanceRoute.decode("add-account:PLANE"))
    }
}
