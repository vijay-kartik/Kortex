package dev.kortex.finance.domain.calc

import dev.kortex.finance.Fixtures.TODAY
import dev.kortex.finance.Fixtures.tx
import dev.kortex.finance.domain.model.BuiltInCategories
import dev.kortex.finance.domain.model.Category
import dev.kortex.finance.domain.model.CategoryKind
import dev.kortex.finance.domain.model.TransactionType.CARD_PAYMENT
import dev.kortex.finance.domain.model.TransactionType.EXPENSE
import dev.kortex.finance.domain.model.TransactionType.INCOME
import dev.kortex.finance.domain.model.TransactionType.TRANSFER
import java.time.LocalDate
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BudgetsTest {
    private val aug = YearMonth.of(2026, 8)
    private val sep = YearMonth.of(2026, 9)
    private val categories = BuiltInCategories.all + Category("fun", "Fun", CategoryKind.EXPENSE, "Rose")

    @Test
    fun `no budgets means the month isn't budgeted`() {
        val txs = listOf(tx(EXPENSE, 100_00, LocalDate.of(2026, 9, 2), "cash", category = "food"))
        val none = Budgets.status(txs, categories, emptyMap(), sep, TODAY)
        assertTrue(none.categories.isEmpty())
        assertNull(none.overall)
        // An income category can't be budgeted, nor can one that's gone.
        val ignored = Budgets.status(txs, categories, mapOf("salary" to 1_000_00, "gone" to 500_00), sep, TODAY)
        assertTrue(ignored.categories.isEmpty())
        assertNull(ignored.overall)
    }

    @Test
    fun `under, at and over budget, most used first`() {
        val txs = listOf(
            tx(EXPENSE, 3_000_00, LocalDate.of(2026, 8, 5), "cash", category = "food"),
            tx(EXPENSE, 1_000_00, LocalDate.of(2026, 8, 10), "kortex", category = "travel"),
            tx(EXPENSE, 600_00, LocalDate.of(2026, 8, 20), "checking", category = "utilities"),
            tx(EXPENSE, 900_00, LocalDate.of(2026, 9, 1), "cash", category = "food"), // September: not August
        )
        val budgets = mapOf("food" to 4_000_00L, "travel" to 1_000_00L, "utilities" to 500_00L)
        val summary = Budgets.status(txs, categories, budgets, aug, TODAY)

        assertEquals(listOf("utilities", "travel", "food"), summary.categories.map { it.categoryUid })
        val (utilities, travel, food) = summary.categories
        assertEquals(120, utilities.percentUsed)
        assertEquals(-100_00, utilities.leftMinor)
        assertEquals(100, travel.percentUsed)
        assertEquals(0, travel.leftMinor)
        assertEquals(75, food.percentUsed)
        assertEquals(1_000_00, food.leftMinor)
        assertEquals("Food", food.name)
        // August is over: the projection is what was spent.
        assertEquals(3_000_00, food.projectedMinor)

        val overall = summary.overall!!
        assertEquals(5_500_00, overall.budgetMinor)
        assertEquals(4_600_00, overall.spentMinor)
        assertEquals(900_00, overall.leftMinor)
        assertEquals(84, overall.percentUsed)
        assertEquals(4_600_00, overall.projectedMinor)
    }

    @Test
    fun `uncategorised and unbudgeted spend stay out`() {
        val txs = listOf(
            tx(EXPENSE, 200_00, LocalDate.of(2026, 8, 3), "cash", category = "food"),
            tx(EXPENSE, 700_00, LocalDate.of(2026, 8, 4), "cash"), // Uncategorised
            tx(EXPENSE, 300_00, LocalDate.of(2026, 8, 5), "cash", category = "fun"), // no budget
            tx(EXPENSE, 400_00, LocalDate.of(2026, 8, 6), "cash", category = "gone"), // deleted category
        )
        val summary = Budgets.status(txs, categories, mapOf("food" to 1_000_00L), aug, TODAY)
        assertEquals(listOf("food"), summary.categories.map { it.categoryUid })
        assertEquals(200_00, summary.categories.single().spentMinor)
        assertEquals(1_000_00, summary.overall!!.budgetMinor)
        assertEquals(200_00, summary.overall!!.spentMinor)
    }

    @Test
    fun `income, transfers and card bills never count as spend`() {
        val txs = listOf(
            tx(INCOME, 5_000_00, LocalDate.of(2026, 8, 1), "checking", category = "food"),
            tx(TRANSFER, 500_00, LocalDate.of(2026, 8, 2), "checking", to = "savings", category = "food"),
            tx(CARD_PAYMENT, 1_400_00, LocalDate.of(2026, 8, 3), "checking", to = "kortex", category = "food"),
            tx(EXPENSE, 250_00, LocalDate.of(2026, 8, 4), "cash", category = "food"),
        )
        val food = Budgets.status(txs, categories, mapOf("food" to 1_000_00L), aug, TODAY).categories.single()
        assertEquals(250_00, food.spentMinor)
        assertEquals(25, food.percentUsed)
    }

    @Test
    fun `early in the month the projection follows the pace`() {
        val today = LocalDate.of(2026, 9, 3)
        val txs = listOf(
            tx(EXPENSE, 200_00, LocalDate.of(2026, 9, 1), "cash", category = "food"),
            tx(EXPENSE, 100_00, LocalDate.of(2026, 9, 3), "cash", category = "food"),
            tx(EXPENSE, 50_00, LocalDate.of(2026, 9, 2), "cash", category = "travel"),
        )
        val budgets = mapOf("food" to 2_000_00L, "travel" to 1_000_00L, "utilities" to 500_00L)
        val summary = Budgets.status(txs, categories, budgets, sep, today)

        val food = summary.categories.first { it.categoryUid == "food" }
        assertEquals(300_00, food.spentMinor)
        assertEquals(15, food.percentUsed)
        assertEquals(3_000_00, food.projectedMinor) // ₹300 in 3 days → ₹3,000 by the 30th: over
        val utilities = summary.categories.first { it.categoryUid == "utilities" }
        assertEquals(0, utilities.spentMinor)
        assertEquals(0, utilities.projectedMinor)
        assertEquals(3_500_00, summary.overall!!.projectedMinor)
        assertEquals(Spending.projectedMonthEndMinor(txs, today), summary.overall!!.projectedMinor)
    }
}
