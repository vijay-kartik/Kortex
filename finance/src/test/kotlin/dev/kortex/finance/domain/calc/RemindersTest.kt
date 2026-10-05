package dev.kortex.finance.domain.calc

import dev.kortex.finance.Fixtures
import dev.kortex.finance.Fixtures.TODAY
import dev.kortex.finance.Fixtures.kortexStatement
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
}
