package dev.kortex.finance.agent

import dev.kortex.finance.FakeFinanceRepository
import dev.kortex.finance.Fixtures
import dev.kortex.finance.Fixtures.TODAY
import dev.kortex.finance.Fixtures.kortexStatement
import dev.kortex.finance.domain.model.TransactionSource
import dev.kortex.finance.domain.model.TransactionType
import dev.kortex.finance.domain.usecase.AddTransaction
import dev.kortex.finance.domain.usecase.MarkPaid
import dev.kortex.finance.domain.usecase.ObserveFinance
import java.time.LocalDate
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class FinanceAgentTest {
    private val repository = FakeFinanceRepository()
    private val add = AddTransaction(repository, Fixtures.clock)
    private val agent = FinanceAgent(ObserveFinance(repository), add, MarkPaid(repository, add, Fixtures.clock), Fixtures.clock)

    @Before
    fun data() {
        repository.accounts.value = listOf(Fixtures.checking, Fixtures.kortex)
        repository.recurring.value = Fixtures.recurring
        repository.statements.value = listOf(kortexStatement)
    }

    @Test
    fun `an expense names its account by last four or name, and its category by name`() = runTest {
        val answer = agent.addEntry(false, 42_50, "8824", "food", "Whole Foods Market", null, null)
        assertTrue(answer.ok)
        assertEquals("Saved ₹42.50 at Whole Foods Market from KORTEX ••8824 on 29 Sep (Food).", answer.text)
        val tx = repository.transactions.value.single()
        assertEquals(TransactionSource.AGENT, tx.source)
        assertEquals("food", tx.categoryUid)

        assertTrue(agent.addEntry(false, 100_00, "checking", null, null, null, LocalDate.of(2026, 9, 20)).ok)
        assertEquals(LocalDate.of(2026, 9, 20), repository.transactions.value.last().occurredOn)
    }

    @Test
    fun `an unclear account or category is sent back to ask`() = runTest {
        val noAccount = agent.addEntry(false, 42_50, null, null, null, null, null)
        assertFalse(noAccount.ok)
        assertEquals("Which account? Checking Account ••4471, KORTEX ••8824.", noAccount.text)
        assertFalse(agent.addEntry(false, 42_50, "kortex", "Gadgets", null, null, null).ok)
        // Income never goes into a card, so with only one other account it's picked.
        assertTrue(agent.addEntry(true, 5_000_00, null, "Salary", "Acme", null, null).ok)
        assertEquals("checking", repository.transactions.value.single().accountUid)
        assertTrue(repository.transactions.value.none { it.type == TransactionType.EXPENSE })
    }

    @Test
    fun `mark paid finds the recurring payment by name`() = runTest {
        val answer = agent.markPaid("netflix", null, null)
        assertTrue(answer.ok)
        assertEquals("Marked Netflix (due 3 Oct) paid: ₹649.00. Next due 3 Nov.", answer.text)
        assertFalse(agent.markPaid("hulu", null, null).ok)
    }

    @Test
    fun `summaries, searches and pending read the same numbers as the screens`() = runTest {
        agent.addEntry(false, 42_50, "8824", "Food", "Whole Foods Market", null, null)
        agent.addEntry(true, 5_000_00, "4471", "Salary", "Acme", null, null)
        assertEquals(
            "September 2026 so far: spent ₹42.50, income ₹5,000.00, kept ₹4,957.50. Where it went: Food ₹42.50 (100%).",
            agent.spendingSummary(null, null).text,
        )
        assertEquals("September 2026 so far: ₹42.50 on Food across 1 entry.", agent.spendingSummary("this_month", "food").text)
        assertFalse(agent.spendingSummary("fortnight", null).ok)

        val found = agent.findTransactions("whole", null, null)
        assertTrue(found.text.startsWith("1 entry, ₹42.50 spent:"))
        assertTrue(found.text.contains("29 Sep: -₹42.50 Whole Foods Market · KORTEX ••8824 · Food"))
        assertEquals("No entries match.", agent.findTransactions("nothing", null, null).text)

        val pending = agent.listPending()
        assertTrue(pending.text.startsWith("₹4,467.00 due in the next 30 days:"))
        assertTrue(pending.text.contains("KORTEX ••8824 bill: ₹1,400.00 due Thu, 15 Oct"))
    }

    @Test
    fun `periods read as people say them`() {
        assertEquals(Triple("August 2026", LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31)), FinanceAgent.periodOf("last month", TODAY))
        assertEquals(LocalDate.of(2026, 1, 1), FinanceAgent.periodOf("this_year", TODAY)!!.second)
        assertEquals(LocalDate.of(2026, 9, 23), FinanceAgent.periodOf("this_week", TODAY)!!.second)
        assertEquals("March 2026", FinanceAgent.periodOf("2026-03", TODAY)!!.first)
    }
}
