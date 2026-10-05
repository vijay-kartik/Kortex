package dev.kortex.finance.ui

import dev.kortex.finance.Fixtures
import dev.kortex.finance.Fixtures.TODAY
import dev.kortex.finance.Fixtures.tx
import dev.kortex.finance.domain.model.BuiltInCategories
import dev.kortex.finance.domain.model.TransactionType
import dev.kortex.finance.domain.usecase.FinanceSnapshot
import dev.kortex.finance.ui.dashboard.DashboardUi
import dev.kortex.finance.ui.dashboard.Tone
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardUiTest {
    private fun snapshot(vararg txs: dev.kortex.finance.domain.model.Transaction) = FinanceSnapshot(
        accounts = listOf(Fixtures.checking),
        transactions = txs.toList(),
        categories = BuiltInCategories.all,
        recurring = emptyList(),
        statements = emptyList(),
    )

    @Test
    fun emptyDataShowsNoInsightsOrPace() {
        val state = DashboardUi.build(FinanceSnapshot(emptyList(), emptyList(), BuiltInCategories.all, emptyList(), emptyList()), TODAY)
        assertFalse(state.loading)
        assertFalse(state.hasAccounts)
        assertEquals(0L, state.totalBalanceMinor)
        assertTrue(state.insights.isEmpty())
        assertNull(state.pace)
        assertTrue(state.shares.isEmpty())
    }

    @Test
    fun spendingLessThanLastMonthByTodayIsGood() {
        val state = DashboardUi.build(
            snapshot(
                tx(TransactionType.OPENING, 10_000_00, LocalDate.of(2026, 8, 1), "checking"),
                tx(TransactionType.EXPENSE, 1_500_00, LocalDate.of(2026, 8, 5), "checking", category = "food"),
                tx(TransactionType.INCOME, 50_000_00, LocalDate.of(2026, 9, 1), "checking", category = "salary"),
                tx(TransactionType.EXPENSE, 1_000_00, LocalDate.of(2026, 9, 10), "checking", category = "food"),
            ),
            TODAY,
        )
        assertTrue(state.hasAccounts)
        assertEquals(1, state.accountCount)
        assertEquals(57_500_00L, state.totalBalanceMinor)
        assertEquals("September", state.monthName)

        val pace = state.insights.first()
        assertEquals("₹500 less", pace.highlight)
        assertEquals(" than August by day 29.", pace.after)
        assertEquals(Tone.GOOD, pace.tone)

        val kept = state.insights[1]
        assertEquals("98%", kept.highlight)

        assertNotNull(state.pace)
        assertEquals(30, state.pace!!.daysInMonth)
        assertEquals(29, state.pace!!.thisMonth.size)

        assertEquals(listOf("Food"), state.shares.map { it.label })
        assertEquals(100, state.shares.single().percent)
        assertTrue(state.flow.last().current)
    }

    @Test
    fun spendingMoreThanLastMonthIsAWarning() {
        val state = DashboardUi.build(
            snapshot(
                tx(TransactionType.EXPENSE, 200_00, LocalDate.of(2026, 8, 3), "checking"),
                tx(TransactionType.EXPENSE, 700_00, LocalDate.of(2026, 9, 3), "checking"),
            ),
            TODAY,
        )
        val insight = state.insights.single()
        assertEquals("₹500 more", insight.highlight)
        assertEquals(Tone.WARN, insight.tone)
        assertEquals("Other", state.shares.single().label)
    }
}
