package dev.kortex.finance.domain.calc

import dev.kortex.finance.Fixtures
import dev.kortex.finance.Fixtures.recurring
import dev.kortex.finance.domain.model.Frequency
import dev.kortex.finance.domain.model.RecurringKind
import java.time.DayOfWeek
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecurringScheduleTest {
    private val rent = Fixtures.monthly("rent", "Rent", RecurringKind.FIXED, 20_000_00, LocalDate.of(2027, 1, 31))

    @Test
    fun `a monthly payment on the 31st falls back in short months and returns`() {
        val feb = RecurringSchedule.nextAfter(rent, LocalDate.of(2027, 1, 31))
        assertEquals(LocalDate.of(2027, 2, 28), feb)
        assertEquals(LocalDate.of(2027, 3, 31), RecurringSchedule.nextAfter(rent, feb))
    }

    @Test
    fun `a yearly payment on 29 Feb falls on the 28th in other years`() {
        val prime = rent.copy(frequency = Frequency.YEARLY, anchorDay = 29, nextDueOn = LocalDate.of(2028, 2, 29))
        assertEquals(LocalDate.of(2029, 2, 28), RecurringSchedule.nextAfter(prime, LocalDate.of(2028, 2, 29)))
    }

    @Test
    fun `a weekly payment keeps its weekday`() {
        val weekly = rent.copy(frequency = Frequency.WEEKLY, anchorDay = DayOfWeek.MONDAY.value, nextDueOn = LocalDate.of(2026, 10, 5))
        val next = RecurringSchedule.nextAfter(weekly, LocalDate.of(2026, 10, 5))
        assertEquals(LocalDate.of(2026, 10, 12), next)
        assertEquals(5, RecurringSchedule.occurrencesUntil(weekly, LocalDate.of(2026, 11, 2)).size)
    }

    @Test
    fun `every second month skips one`() {
        val bimonthly = rent.copy(interval = 2, anchorDay = 10, nextDueOn = LocalDate.of(2026, 10, 10))
        assertEquals(LocalDate.of(2026, 12, 10), RecurringSchedule.nextAfter(bimonthly, LocalDate.of(2026, 10, 10)))
    }

    @Test
    fun `occurrences start at the next due day, overdue included, and stop when paused`() {
        val netflix = recurring.first { it.uid == "netflix" }
        assertEquals(listOf(LocalDate.of(2026, 10, 3)), RecurringSchedule.occurrencesUntil(netflix, LocalDate.of(2026, 10, 29)))
        val overdue = netflix.copy(nextDueOn = LocalDate.of(2026, 9, 3))
        assertEquals(
            listOf(LocalDate.of(2026, 9, 3), LocalDate.of(2026, 10, 3)),
            RecurringSchedule.occurrencesUntil(overdue, LocalDate.of(2026, 10, 29)),
        )
        assertTrue(RecurringSchedule.occurrencesUntil(netflix.copy(paused = true), LocalDate.of(2027, 1, 1)).isEmpty())
    }

    @Test
    fun `per month normalises weekly and yearly amounts`() {
        assertEquals(433_33, RecurringSchedule.perMonthMinor(rent.copy(frequency = Frequency.WEEKLY, amountMinor = 100_00, anchorDay = 1)))
        assertEquals(124_92, RecurringSchedule.perMonthMinor(rent.copy(frequency = Frequency.YEARLY, amountMinor = 1_499_00)))
        assertEquals(10_000_00, RecurringSchedule.perMonthMinor(rent.copy(interval = 2)))
    }

    @Test
    fun `totals match Recurring 01`() {
        val totals = RecurringSchedule.totals(recurring)
        assertEquals(3_067_00, totals.perMonthMinor)
        assertEquals(768_00, totals.subscriptionsPerMonthMinor)
        assertEquals(2, totals.subscriptionCount)
        assertEquals(2_299_00, totals.fixedPerMonthMinor)
        assertEquals(2, totals.fixedCount)
        assertEquals(36_804_00, totals.perYearMinor)
        assertEquals(2_418_00, RecurringSchedule.totals(recurring.map { if (it.uid == "netflix") it.copy(paused = true) else it }).perMonthMinor)
    }
}
