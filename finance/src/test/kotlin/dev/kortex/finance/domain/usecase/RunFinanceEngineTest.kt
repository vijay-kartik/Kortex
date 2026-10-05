package dev.kortex.finance.domain.usecase

import dev.kortex.finance.FakeFinanceRepository
import dev.kortex.finance.Fixtures
import dev.kortex.finance.Fixtures.kortexStatement
import dev.kortex.finance.domain.FinanceIds
import dev.kortex.finance.domain.model.StatementSource
import dev.kortex.finance.domain.model.TransactionType
import java.time.LocalDate
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RunFinanceEngineTest {
    private val repository = FakeFinanceRepository()
    private val markPaid = MarkPaid(repository, AddTransaction(repository, Fixtures.clock), Fixtures.clock)
    private val engine = RunFinanceEngine(repository, markPaid, Fixtures.clock)

    @Before
    fun accounts() {
        repository.accounts.value = listOf(Fixtures.checking, Fixtures.kortex)
    }

    @Test
    fun `a card gets an AUTO statement for what it owed on its statement day, once`() = runTest {
        repository.transactions.value = listOf(
            Fixtures.tx(TransactionType.EXPENSE, 1_000_00, LocalDate.of(2026, 9, 10), "kortex"),
            Fixtures.tx(TransactionType.EXPENSE, 400_00, LocalDate.of(2026, 9, 20), "kortex"),
            // After the statement day: next month's bill.
            Fixtures.tx(TransactionType.EXPENSE, 42_50, LocalDate.of(2026, 9, 28), "kortex"),
        )
        assertEquals(1, engine().statementsCreated)
        val statement = repository.statements.value.single()
        assertEquals(FinanceIds.statement("kortex", LocalDate.of(2026, 9, 25)), statement.uid)
        assertEquals(LocalDate.of(2026, 10, 15), statement.dueOn)
        assertEquals(1_400_00L, statement.totalDueMinor)
        assertEquals(200_00L, statement.minDueMinor)
        assertEquals(StatementSource.AUTO, statement.source)

        assertEquals(0, engine().statementsCreated)
        assertEquals(1, repository.statements.value.size)
    }

    @Test
    fun `a statement from an SMS is never replaced`() = runTest {
        val fromSms = kortexStatement.copy(uid = FinanceIds.statement("kortex", kortexStatement.statementOn), source = StatementSource.SMS)
        repository.statements.value = listOf(fromSms)
        repository.transactions.value = listOf(Fixtures.tx(TransactionType.EXPENSE, 9_999_00, LocalDate.of(2026, 9, 10), "kortex"))
        engine()
        assertEquals(listOf(fromSms), repository.statements.value)
    }

    @Test
    fun `auto-debits due by today are recorded on their due day`() = runTest {
        val gym = Fixtures.recurring.first { it.uid == "gym" }.copy(autoMarkPaid = true, nextDueOn = LocalDate.of(2026, 9, 5))
        repository.recurring.value = listOf(gym)
        assertEquals(1, engine().autoPaid)
        val tx = repository.transactions.value.single()
        assertEquals(LocalDate.of(2026, 9, 5), tx.occurredOn)
        assertEquals(LocalDate.of(2026, 9, 5), tx.dueOn)
        assertEquals(LocalDate.of(2026, 10, 5), repository.getRecurring("gym")!!.nextDueOn)
        assertEquals(0, engine().autoPaid)
    }

    @Test
    fun `an auto-debit with no account waits in pending`() = runTest {
        val gym = Fixtures.recurring.first { it.uid == "gym" }.copy(autoMarkPaid = true, nextDueOn = LocalDate.of(2026, 9, 5))
        repository.recurring.value = listOf(gym)
        repository.deleteAccount("checking")
        assertEquals(0, engine().autoPaid)
        assertTrue(repository.transactions.value.isEmpty())
        assertEquals(LocalDate.of(2026, 9, 5), repository.getRecurring("gym")!!.nextDueOn)
    }

    @Test
    fun `a payment made on another phone moves the due date on`() = runTest {
        val oct3 = LocalDate.of(2026, 10, 3)
        repository.recurring.value = Fixtures.recurring.filter { it.uid == "netflix" }
        repository.transactions.value = listOf(
            Fixtures.tx(TransactionType.EXPENSE, 649_00, Fixtures.TODAY, "kortex", recurringUid = "netflix", dueOn = oct3)
                .copy(uid = FinanceIds.recurringOccurrence("netflix", oct3)),
        )
        assertEquals(1, engine().caughtUp)
        assertEquals(LocalDate.of(2026, 11, 3), repository.getRecurring("netflix")!!.nextDueOn)
    }
}
