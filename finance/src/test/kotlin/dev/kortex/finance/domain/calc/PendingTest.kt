package dev.kortex.finance.domain.calc

import dev.kortex.finance.Fixtures.TODAY
import dev.kortex.finance.Fixtures.kortexStatement
import dev.kortex.finance.Fixtures.recurring
import dev.kortex.finance.Fixtures.tx
import dev.kortex.finance.domain.model.TransactionType.CARD_PAYMENT
import dev.kortex.finance.domain.model.TransactionType.EXPENSE
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingTest {
    private val statements = listOf(kortexStatement)

    @Test
    fun `pending matches the dashboard tile and Pending payments`() {
        val pending = Pending.summary(TODAY, statements, recurring, emptyList())
        assertEquals(4_467_00, pending.totalMinor)
        assertEquals(5, pending.count)
        assertEquals(1_400_00, pending.cardBillsMinor)
        assertEquals(1, pending.cardBillCount)
        assertEquals(3_067_00, pending.recurringMinor)
        assertEquals(4, pending.recurringCount)
        assertEquals(listOf("Netflix", "Gym membership", "Airtel Fiber", "Spotify", "kortex"), pending.items.map { it.title })
        assertEquals(200_00L, pending.items.last().minDueMinor)
        assertEquals(20_383_12, Pending.leftAfterPendingMinor(24_850_12, pending))
    }

    @Test
    fun `marking Netflix paid drops it, as on Recurring 05`() {
        val paid = tx(EXPENSE, 649_00, TODAY, "kortex", recurringUid = "netflix", dueOn = LocalDate.of(2026, 10, 3))
        val advanced = recurring.map { if (it.uid == "netflix") it.copy(nextDueOn = LocalDate.of(2026, 11, 3)) else it }
        val pending = Pending.summary(TODAY, statements, advanced, listOf(paid))
        assertEquals(3_818_00, pending.totalMinor)
        assertEquals(21_032_12, Pending.leftAfterPendingMinor(24_850_12, pending))
        // Even before the due date moves on, the paid occurrence isn't counted twice.
        assertEquals(3_818_00, Pending.summary(TODAY, statements, recurring, listOf(paid)).totalMinor)
    }

    @Test
    fun `a paid bill leaves pending and a part-paid one shows what's left`() {
        val full = tx(CARD_PAYMENT, 1_400_00, TODAY, "checking", to = "kortex", statementUid = kortexStatement.uid)
        assertEquals(0, Pending.summary(TODAY, statements, emptyList(), listOf(full)).cardBillCount)
        val part = full.copy(amountMinor = 1_000_00)
        val bill = Pending.summary(TODAY, statements, emptyList(), listOf(part)).items.single()
        assertEquals(400_00, bill.amountMinor)
        assertEquals(0L, bill.minDueMinor)
    }

    @Test
    fun `anything past the 30-day horizon waits`() {
        val later = recurring.map { it.copy(nextDueOn = it.nextDueOn.plusMonths(1)) }
        assertTrue(Pending.summary(TODAY, emptyList(), later, emptyList()).items.isEmpty())
    }

    @Test
    fun `due dates are amber within 7 days and alarm once past`() {
        assertEquals(DueUrgency.SOON, Pending.urgency(LocalDate.of(2026, 10, 3), TODAY))
        assertEquals(DueUrgency.SOON, Pending.urgency(LocalDate.of(2026, 10, 5), TODAY))
        assertEquals(DueUrgency.SOON, Pending.urgency(TODAY, TODAY))
        assertEquals(DueUrgency.LATER, Pending.urgency(LocalDate.of(2026, 10, 10), TODAY))
        assertEquals(DueUrgency.OVERDUE, Pending.urgency(LocalDate.of(2026, 9, 28), TODAY))
    }
}
