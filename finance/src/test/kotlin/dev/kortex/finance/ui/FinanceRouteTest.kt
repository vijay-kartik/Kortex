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
