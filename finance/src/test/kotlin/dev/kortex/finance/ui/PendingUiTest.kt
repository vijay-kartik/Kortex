package dev.kortex.finance.ui

import dev.kortex.finance.Fixtures
import dev.kortex.finance.Fixtures.TODAY
import dev.kortex.finance.Fixtures.kortexStatement
import dev.kortex.finance.domain.calc.DueUrgency
import dev.kortex.finance.domain.model.BuiltInCategories
import dev.kortex.finance.domain.model.TransactionType
import dev.kortex.finance.domain.usecase.FinanceSnapshot
import dev.kortex.finance.ui.pending.PendingFilter
import dev.kortex.finance.ui.pending.PendingUi
import dev.kortex.finance.ui.recurring.RecurringFilter
import dev.kortex.finance.ui.recurring.RecurringListUi
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The numbers on Figma's Pending payments and Recurring 01. */
class PendingUiTest {
    private val snapshot = FinanceSnapshot(
        accounts = listOf(Fixtures.checking, Fixtures.kortex),
        transactions = listOf(Fixtures.tx(TransactionType.OPENING, 24_850_12, LocalDate.of(2026, 9, 1), "checking")),
        categories = BuiltInCategories.all,
        recurring = Fixtures.recurring,
        statements = listOf(kortexStatement),
    )

    @Test
    fun pendingMatchesTheScreen() {
        val state = PendingUi.build(snapshot, TODAY)
        assertEquals(4_467_00L, state.totalMinor)
        assertEquals(1_400_00L, state.cardBillsMinor)
        assertEquals(3_067_00L, state.recurringMinor)
        assertEquals(20_383_12L, state.leftAfterMinor)
        assertEquals(mapOf(PendingFilter.ALL to 5, PendingFilter.CARDS to 1, PendingFilter.SUBS to 2, PendingFilter.FIXED to 2), state.counts)

        assertEquals(listOf("Next 7 days", "Later in October"), state.groups.map { it.label })
        assertEquals(listOf("Netflix", "Gym membership"), state.groups[0].rows.map { it.title })
        assertTrue(state.groups[0].rows.all { it.urgency == DueUrgency.SOON })
        assertEquals("Subscription · monthly", state.groups[0].rows[0].subtitle)
        val bill = state.groups[1].rows.last()
        assertEquals("KORTEX ••8824", bill.title)
        assertEquals("Card bill · min ₹200", bill.subtitle)
    }

    @Test
    fun filtersAndMissingAccounts() {
        val cards = PendingUi.build(snapshot, TODAY, PendingFilter.CARDS)
        assertEquals(listOf("KORTEX ••8824"), cards.groups.flatMap { it.rows }.map { it.title })

        val noChecking = snapshot.copy(accounts = listOf(Fixtures.kortex))
        val gym = PendingUi.build(noChecking, TODAY).groups.flatMap { it.rows }.first { it.title == "Gym membership" }
        assertTrue(gym.needsAccount)
        assertEquals("Needs an account", gym.subtitle)
    }

    @Test
    fun recurringListMatchesTheScreen() {
        val state = RecurringListUi.build(snapshot, TODAY)
        assertEquals(3_067_00L, state.perMonthMinor)
        assertEquals(768_00L, state.subscriptionsMinor)
        assertEquals(2_299_00L, state.fixedMinor)
        assertEquals(36_804_00L, state.perYearMinor)
        assertEquals("Netflix · 3 Oct", state.nextDue)
        assertEquals(listOf("Subscriptions", "Fixed expenses"), state.groups.map { it.label })
        val netflix = state.groups[0].rows[0]
        assertEquals("Monthly · KORTEX ••8824", netflix.subtitle)
        assertEquals("Next 3 Oct", netflix.next)
        assertEquals(DueUrgency.SOON, netflix.urgency)
        assertEquals("Monthly · Checking Account ••4471", state.groups[1].rows[0].subtitle)

        assertEquals(listOf("Fixed expenses"), RecurringListUi.build(snapshot, TODAY, RecurringFilter.FIXED).groups.map { it.label })
    }
}
