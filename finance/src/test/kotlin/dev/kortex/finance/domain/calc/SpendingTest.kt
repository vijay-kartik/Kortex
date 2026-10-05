package dev.kortex.finance.domain.calc

import dev.kortex.finance.Fixtures.TODAY
import dev.kortex.finance.Fixtures.tx
import dev.kortex.finance.domain.model.BuiltInCategories
import dev.kortex.finance.domain.model.Category
import dev.kortex.finance.domain.model.CategoryKind
import dev.kortex.finance.domain.model.TransactionType.CARD_PAYMENT
import dev.kortex.finance.domain.model.TransactionType.EXPENSE
import dev.kortex.finance.domain.model.TransactionType.INCOME
import dev.kortex.finance.domain.model.TransactionType.OPENING
import dev.kortex.finance.domain.model.TransactionType.TRANSFER
import java.time.LocalDate
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpendingTest {
    private val sep = YearMonth.of(2026, 9)

    @Test
    fun `month to date compares the same days of last month`() {
        val txs = listOf(
            tx(EXPENSE, 3_180_50, LocalDate.of(2026, 9, 10), "kortex"),
            tx(EXPENSE, 3_618_50, LocalDate.of(2026, 8, 20), "checking"),
            tx(EXPENSE, 900_00, LocalDate.of(2026, 8, 30), "checking"), // after day 29: not compared
        )
        val mtd = Spending.monthToDate(txs, TODAY)
        assertEquals(29, mtd.day)
        assertEquals(-438_00, mtd.deltaMinor) // "₹438 less than August by day 29"
    }

    @Test
    fun `month to date stops at the end of a shorter last month`() {
        val txs = listOf(tx(EXPENSE, 100_00, LocalDate.of(2027, 2, 28), "cash"))
        assertEquals(100_00, Spending.monthToDate(txs, LocalDate.of(2027, 3, 30)).lastMonthMinor)
    }

    @Test
    fun `pace projects the month from the days gone`() {
        val txs = listOf(tx(EXPENSE, 3_180_50, LocalDate.of(2026, 9, 5), "kortex"))
        assertEquals(3_290_17, Spending.projectedMonthEndMinor(txs, TODAY)) // "month ends near ₹3,290"
    }

    @Test
    fun `only expenses and income count, never transfers, card bills or openings`() {
        val txs = listOf(
            tx(OPENING, 10_000_00, LocalDate.of(2026, 9, 1), "checking"),
            tx(INCOME, 5_420_00, LocalDate.of(2026, 9, 1), "checking"),
            tx(EXPENSE, 3_180_50, LocalDate.of(2026, 9, 12), "kortex"),
            tx(CARD_PAYMENT, 1_400_00, LocalDate.of(2026, 9, 14), "checking", to = "kortex"),
            tx(TRANSFER, 500_00, LocalDate.of(2026, 9, 15), "checking", to = "savings"),
        )
        val month = Spending.month(txs, sep)
        assertEquals(3_180_50, month.spentMinor)
        assertEquals(5_420_00, month.incomeMinor)
        assertEquals(2_239_50, month.savingsMinor)
        val flow = Spending.monthlyFlows(txs, sep).last()
        assertEquals(41, flow.keptPercent) // "You kept 41%"
        assertEquals(2_239_50, flow.netMinor) // "September so far +₹2,239.50"
    }

    @Test
    fun `cash flow covers six months, oldest first`() {
        val flows = Spending.monthlyFlows(emptyList(), sep)
        assertEquals((4..9).map { YearMonth.of(2026, it) }, flows.map { it.month })
        assertNull(flows.first().keptPercent)
    }

    @Test
    fun `year to date runs from January 1`() {
        val txs = listOf(
            tx(EXPENSE, 100_00, LocalDate.of(2025, 12, 31), "cash"),
            tx(EXPENSE, 200_00, LocalDate.of(2026, 1, 1), "cash"),
            tx(INCOME, 1_000_00, LocalDate.of(2026, 6, 1), "checking"),
        )
        val year = Spending.yearToDate(txs, TODAY)
        assertEquals(200_00, year.spentMinor)
        assertEquals(0.8, year.savingsRate!!, 1e-9)
    }

    @Test
    fun `where it went shows four categories and folds the rest into Other`() {
        val shopping = Category("shop", "Shopping", CategoryKind.EXPENSE, "Lilac")
        val health = Category("health", "Health", CategoryKind.EXPENSE, "Mint")
        val categories = BuiltInCategories.all + shopping + health
        val day = LocalDate.of(2026, 9, 10)
        val txs = listOf(
            tx(EXPENSE, 390_00, day, "kortex", category = "food"),
            tx(EXPENSE, 190_00, day, "kortex", category = "travel"),
            tx(EXPENSE, 160_00, day, "kortex", category = "utilities"),
            tx(EXPENSE, 150_00, day, "kortex", category = "shop"),
            tx(EXPENSE, 60_00, day, "kortex", category = "health"),
            tx(EXPENSE, 50_00, day, "kortex"),
            tx(EXPENSE, 999_00, LocalDate.of(2026, 8, 31), "kortex", category = "food"),
        )
        val shares = Spending.whereItWent(txs, categories, sep.atDay(1), sep.atEndOfMonth())
        assertEquals(listOf("Food", "Travel", "Utilities", "Shopping", null), shares.map { it.category?.name })
        assertEquals(listOf(39, 19, 16, 15, 11), shares.map { it.percent })
        assertEquals(110_00, shares.last().amountMinor)
    }

    @Test
    fun `entries of a deleted category count as Other`() {
        val txs = listOf(tx(EXPENSE, 100_00, TODAY, "cash", category = "deleted"))
        val shares = Spending.whereItWent(txs, BuiltInCategories.all, sep.atDay(1), sep.atEndOfMonth())
        assertNull(shares.single().category)
        assertEquals(100, shares.single().percent)
    }

    @Test
    fun `daily lists the day newest first and last days sums each day`() {
        val lunch = tx(EXPENSE, 245_00, TODAY, "kortex", atMillis = 2_000)
        val coffee = tx(EXPENSE, 99_20, TODAY, "kortex", atMillis = 1_000)
        val yesterday = tx(EXPENSE, 50_00, TODAY.minusDays(1), "cash")
        val txs = listOf(coffee, lunch, yesterday)
        assertEquals(listOf(lunch, coffee), Spending.daily(txs, TODAY))
        val week = Spending.lastDays(txs, TODAY)
        assertEquals(7, week.size)
        assertEquals(TODAY to 344_20L, week.last())
        assertEquals(50_00L, week[5].second)
    }

    @Test
    fun `entries per category`() {
        val txs = listOf(tx(EXPENSE, 1, TODAY, "cash", category = "food"), tx(EXPENSE, 1, TODAY, "cash", category = "food"), tx(EXPENSE, 1, TODAY, "cash"))
        assertEquals(mapOf("food" to 2), Spending.entriesPerCategory(txs))
    }

    @Test
    fun `rounded percents always add up to 100`() {
        assertEquals(listOf(34, 33, 33), roundedPercents(listOf(1, 1, 1)))
        assertEquals(listOf(0, 0), roundedPercents(listOf(0, 0)))
        assertEquals(100, roundedPercents(listOf(124_050, 60_430, 50_890, 47_710, 34_970)).sum())
    }
}
