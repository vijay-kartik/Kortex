package dev.kortex.finance.domain.calc

import dev.kortex.finance.Fixtures
import dev.kortex.finance.Fixtures.TODAY
import dev.kortex.finance.Fixtures.kortexStatement
import dev.kortex.finance.domain.model.BuiltInCategories
import dev.kortex.finance.domain.model.Transaction
import dev.kortex.finance.domain.model.TransactionType
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RemindersTest {
    private val accounts = listOf(Fixtures.checking, Fixtures.kortex)
    private val netflix = Fixtures.recurring.first { it.uid == "netflix" }

    @Test
    fun `a recurring payment is reminded its chosen number of days before`() {
        val reminders = Reminders.due(TODAY, accounts, listOf(netflix.copy(remindDaysBefore = 4)), emptyList(), emptyList())
        val reminder = reminders.single()
        assertEquals("Netflix", reminder.name)
        assertEquals(4, reminder.daysLeft)
        assertEquals(PendingKind.SUBSCRIPTION, reminder.kind)

        assertTrue(Reminders.due(TODAY, accounts, listOf(netflix.copy(remindDaysBefore = 2)), emptyList(), emptyList()).isEmpty())
        assertTrue(Reminders.due(TODAY, accounts, listOf(netflix), emptyList(), emptyList()).isEmpty())
    }

    @Test
    fun `paused or already paid payments aren't reminded`() {
        val due = netflix.copy(remindDaysBefore = 4)
        assertTrue(Reminders.due(TODAY, accounts, listOf(due.copy(paused = true)), emptyList(), emptyList()).isEmpty())
        val paid = Fixtures.tx(TransactionType.EXPENSE, 649_00, TODAY, "kortex", recurringUid = "netflix", dueOn = due.nextDueOn)
        assertTrue(Reminders.due(TODAY, accounts, listOf(due), emptyList(), listOf(paid)).isEmpty())
    }

    @Test
    fun `an unpaid card bill is reminded three days before and on the day`() {
        val oct12 = LocalDate.of(2026, 10, 12)
        val bill = Reminders.due(oct12, accounts, emptyList(), listOf(kortexStatement), emptyList()).single()
        assertEquals("KORTEX", bill.name)
        assertEquals(1_400_00L, bill.amountMinor)
        assertEquals(3, bill.daysLeft)
        assertEquals(0, Reminders.due(kortexStatement.dueOn, accounts, emptyList(), listOf(kortexStatement), emptyList()).single().daysLeft)
        assertTrue(Reminders.due(LocalDate.of(2026, 10, 13), accounts, emptyList(), listOf(kortexStatement), emptyList()).isEmpty())

        val paid = Fixtures.tx(TransactionType.CARD_PAYMENT, 1_400_00, TODAY, "checking", to = "kortex", statementUid = kortexStatement.uid)
        assertTrue(Reminders.due(oct12, accounts, emptyList(), listOf(kortexStatement), listOf(paid)).isEmpty())
    }

    private val budgets = mapOf("food" to 8_000_00L)

    private fun food(amountMinor: Long, on: LocalDate) = Fixtures.tx(TransactionType.EXPENSE, amountMinor, on, "cash", category = "food")

    /** Each day's budget reminders, as the daily job would see them with [txs] recorded. */
    private fun budgetReminders(today: LocalDate, txs: List<Transaction>) =
        Reminders.due(today, accounts, emptyList(), emptyList(), txs, BuiltInCategories.all, budgets).filter { it.kind == PendingKind.BUDGET }

    @Test
    fun `a budget is reminded only on the day it reaches 80 percent and the day it goes over`() {
        val txs = listOf(
            food(6_000_00, LocalDate.of(2026, 9, 10)),
            food(500_00, LocalDate.of(2026, 9, 14)),
            food(1_000_00, LocalDate.of(2026, 9, 18)),
            food(1_000_00, LocalDate.of(2026, 9, 22)),
        )
        assertTrue(budgetReminders(LocalDate.of(2026, 9, 10), txs).isEmpty())
        val near = budgetReminders(LocalDate.of(2026, 9, 14), txs).single()
        assertEquals("bud:food:2026-09:80", near.key)
        assertEquals("Food", near.name)
        assertEquals(8_000_00L, near.amountMinor)
        assertEquals(6_500_00L, near.spentMinor)
        assertEquals(80, near.threshold)
        assertTrue(budgetReminders(LocalDate.of(2026, 9, 15), txs).isEmpty())
        // Exactly at the budget isn't over it.
        assertTrue(budgetReminders(LocalDate.of(2026, 9, 18), txs).isEmpty())
        val over = budgetReminders(LocalDate.of(2026, 9, 22), txs).single()
        assertEquals("bud:food:2026-09:100", over.key)
        assertEquals(8_500_00L, over.spentMinor)
        assertTrue(budgetReminders(LocalDate.of(2026, 9, 23), txs).isEmpty())
    }

    @Test
    fun `spend that jumps from under 80 percent to over budget gets both reminders that day only`() {
        val txs = listOf(food(1_000_00, LocalDate.of(2026, 9, 2)), food(7_500_00, LocalDate.of(2026, 9, 9)))
        assertEquals(
            listOf("bud:food:2026-09:80", "bud:food:2026-09:100"),
            budgetReminders(LocalDate.of(2026, 9, 9), txs).map { it.key },
        )
        assertTrue(budgetReminders(LocalDate.of(2026, 9, 10), txs).isEmpty())
    }

    @Test
    fun `only this month counts, so the 1st starts from nothing`() {
        val txs = listOf(food(9_000_00, LocalDate.of(2026, 9, 30)), food(8_500_00, LocalDate.of(2026, 10, 1)))
        assertEquals(
            listOf("bud:food:2026-10:80", "bud:food:2026-10:100"),
            budgetReminders(LocalDate.of(2026, 10, 1), txs).map { it.key },
        )
    }

    @Test
    fun `later spend and unbudgeted categories don't count`() {
        // Recorded ahead of time: it isn't spent until its day.
        assertTrue(budgetReminders(LocalDate.of(2026, 9, 9), listOf(food(9_000_00, LocalDate.of(2026, 9, 20)))).isEmpty())
        val travel = Fixtures.tx(TransactionType.EXPENSE, 50_000_00, LocalDate.of(2026, 9, 9), "cash", category = "travel")
        assertTrue(budgetReminders(LocalDate.of(2026, 9, 9), listOf(travel)).isEmpty())
        // Without budgets, only the payment and bill reminders.
        val spent = listOf(food(9_000_00, TODAY))
        assertTrue(Reminders.due(TODAY, accounts, emptyList(), emptyList(), spent, BuiltInCategories.all, emptyMap()).isEmpty())
    }
}
