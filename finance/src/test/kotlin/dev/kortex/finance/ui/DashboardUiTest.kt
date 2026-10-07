package dev.kortex.finance.ui

import dev.kortex.finance.Fixtures
import dev.kortex.finance.Fixtures.TODAY
import dev.kortex.finance.Fixtures.tx
import dev.kortex.finance.domain.model.BuiltInCategories
import dev.kortex.finance.domain.model.Category
import dev.kortex.finance.domain.model.CategoryKind
import dev.kortex.finance.domain.model.TransactionType
import dev.kortex.finance.domain.usecase.FinanceSnapshot
import dev.kortex.finance.ui.common.BudgetLevel
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

    private val shopping = Category("shop", "Shopping", CategoryKind.EXPENSE, "Lilac", sortOrder = 3)

    /** Budgets · 05: Food ₹5,400 of ₹8,000, Travel ₹2,580 of ₹3,000, Shopping ₹4,480 of ₹4,000. */
    private fun budgeted(budgets: Map<String, Long>) = DashboardUi.build(
        snapshot(
            tx(TransactionType.EXPENSE, 5_400_00, LocalDate.of(2026, 9, 10), "checking", category = "food"),
            tx(TransactionType.EXPENSE, 2_580_00, LocalDate.of(2026, 9, 12), "checking", category = "travel"),
            tx(TransactionType.EXPENSE, 4_480_00, LocalDate.of(2026, 9, 20), "checking", category = "shop"),
        ).let { it.copy(categories = it.categories + shopping) },
        TODAY,
        budgets,
    )

    @Test
    fun noBudgetHidesTheCardAndTheInsight() {
        val state = budgeted(emptyMap())
        assertNull(state.budgets)
        assertTrue(state.insights.none { it.highlight.endsWith(" over") })
    }

    @Test
    fun budgetsCardMatchesTheFrame() {
        val budgets = budgeted(mapOf("food" to 8_000_00L, "travel" to 3_000_00L, "shop" to 4_000_00L)).budgets!!
        assertEquals("₹12,460", budgets.overall.spent)
        assertEquals("₹15,000", budgets.overall.budget)
        assertEquals(83, budgets.percentUsed)
        assertEquals(BudgetLevel.NEAR, budgets.overall.level)
        assertEquals("₹2,540 left", budgets.left)
        assertEquals("Month ends near ₹12,890", budgets.projection)

        // Most-used first, as Budgets.status orders them.
        assertEquals(listOf("Shopping", "Travel", "Food"), budgets.categories.map { it.name })
        assertEquals(listOf("Lilac", "Teal", "Synapse"), budgets.categories.map { it.colorToken })
        assertEquals(listOf(BudgetLevel.OVER, BudgetLevel.NEAR, BudgetLevel.UNDER), budgets.categories.map { it.progress.level })
    }

    @Test
    fun insightNamesTheLargestProjectedOvershoot() {
        val state = budgeted(mapOf("food" to 8_000_00L, "travel" to 3_000_00L, "shop" to 4_000_00L))
        val insight = state.insights.last()
        // ₹4,480 by day 29 of 30 ends near ₹4,634: ₹634 over. Travel ends near ₹2,669, under ₹3,000.
        assertEquals("Shopping is on track to go ", insight.before)
        assertEquals("₹634 over", insight.highlight)
        assertEquals(" budget.", insight.after)
        assertEquals(Tone.WARN, insight.tone)

        // Travel's ₹2,669 beats a ₹2,000 budget by ₹669, more than Shopping's ₹634.
        val travel = budgeted(mapOf("travel" to 2_000_00L, "shop" to 4_000_00L)).insights.last()
        assertEquals("Travel is on track to go ", travel.before)
        assertEquals("₹669 over", travel.highlight)
    }

    @Test
    fun overallOverBudgetSaysHowMuchOver() {
        val budgets = budgeted(mapOf("shop" to 4_000_00L)).budgets!!
        assertEquals("₹480 over", budgets.left)
        assertEquals(BudgetLevel.OVER, budgets.overall.level)
        assertEquals(1f, budgets.overall.fraction)
    }

    @Test
    fun budgetsUnderPaceGetNoInsight() {
        val state = budgeted(mapOf("food" to 8_000_00L))
        assertTrue(state.insights.none { it.highlight.endsWith(" over") })
        assertNotNull(state.budgets)
    }
}
