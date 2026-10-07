package dev.kortex.finance.ui

import dev.kortex.finance.domain.calc.PendingKind
import dev.kortex.finance.domain.calc.Reminder
import dev.kortex.finance.reminders.FinanceReminders
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

class FinanceRemindersTest {
    private val netflix = Reminder("k", PendingKind.SUBSCRIPTION, "Netflix", 649_00, LocalDate.of(2026, 10, 3), daysLeft = 2)

    @Test
    fun titlesSayWhatAndWhen() {
        assertEquals("Netflix ₹649 due in 2 days", FinanceReminders.title(netflix))
        assertEquals("Netflix ₹649 due tomorrow", FinanceReminders.title(netflix.copy(daysLeft = 1)))
        assertEquals("Netflix ₹649 will be auto-debited today", FinanceReminders.title(netflix.copy(daysLeft = 0, automatic = true)))
        assertEquals("KORTEX bill ₹1,400 due in 3 days", FinanceReminders.title(Reminder("b", PendingKind.CARD_BILL, "KORTEX", 1_400_00, LocalDate.of(2026, 10, 15), 3)))
        assertEquals("Due Sat, 3 Oct · tap to mark it paid", FinanceReminders.text(netflix))
    }

    @Test
    fun budgetsSayHowMuchIsUsedOrOver() {
        val near = Reminder("n", PendingKind.BUDGET, "Food", 8_000_00, LocalDate.of(2026, 10, 14), 0, spentMinor = 6_800_00, threshold = 80)
        assertEquals("Food: 85 % of ₹8,000 used", FinanceReminders.title(near))
        assertEquals("₹1,200 left for October", FinanceReminders.text(near))
        val over = near.copy(spentMinor = 8_600_00, threshold = 100)
        assertEquals("Food is ₹600 over budget", FinanceReminders.title(over))
        assertEquals("₹8,600 spent of ₹8,000 in October", FinanceReminders.text(over))
    }

    @Test
    fun theJobStartsAtTheNextNineOClock() {
        val zone = ZoneId.of("Asia/Kolkata")
        val morning = ZonedDateTime.of(2026, 9, 29, 7, 30, 0, 0, zone)
        assertEquals(Duration.ofMinutes(90), FinanceReminders.untilNext(LocalTime.of(9, 0), morning))
        val evening = ZonedDateTime.of(2026, 9, 29, 21, 0, 0, 0, zone)
        assertEquals(Duration.ofHours(12), FinanceReminders.untilNext(LocalTime.of(9, 0), evening))
    }
}
