package dev.kortex.finance.domain.usecase

import dev.kortex.finance.FakeFinanceRepository
import dev.kortex.finance.Fixtures
import dev.kortex.finance.Fixtures.TODAY
import dev.kortex.finance.domain.FinanceIds
import dev.kortex.finance.domain.model.Frequency
import dev.kortex.finance.domain.model.RecurringKind
import dev.kortex.finance.domain.model.TransactionSource
import dev.kortex.finance.domain.model.TransactionType
import java.time.LocalDate
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RecurringPaymentsTest {
    private val repository = FakeFinanceRepository()
    private val addTransaction = AddTransaction(repository, Fixtures.clock)
    private val markPaid = MarkPaid(repository, addTransaction, Fixtures.clock)
    private val skip = SkipOccurrence(repository)
    private val undo = UndoOccurrence(repository)
    private val save = SaveRecurring(repository, Fixtures.clock)

    private val oct3 = LocalDate.of(2026, 10, 3)
    private val nov3 = LocalDate.of(2026, 11, 3)

    @Before
    fun data() {
        repository.accounts.value = listOf(Fixtures.checking, Fixtures.kortex)
        repository.recurring.value = Fixtures.recurring.map { if (it.uid == "netflix") it.copy(categoryUid = "utilities") else it }
    }

    private suspend fun netflix() = repository.getRecurring("netflix")!!

    @Test
    fun `mark as paid records the expense and moves the next due date`() = runTest {
        val result = markPaid("netflix", oct3) as OccurrenceResult.Paid
        assertEquals(nov3, result.nextDueOn)
        assertEquals(nov3, netflix().nextDueOn)
        assertEquals(oct3, result.previous.nextDueOn)

        val tx = repository.getTransaction(result.transactionUid)!!
        assertEquals(FinanceIds.recurringOccurrence("netflix", oct3), tx.uid)
        assertEquals(TransactionType.EXPENSE, tx.type)
        assertEquals(649_00L, tx.amountMinor)
        assertEquals("kortex", tx.accountUid)
        assertEquals("utilities", tx.categoryUid)
        assertEquals("Netflix", tx.merchant)
        assertEquals(TransactionSource.RECURRING, tx.source)
        assertEquals(TODAY, tx.occurredOn)
        assertEquals(oct3, tx.dueOn)
        assertEquals("utilities", repository.merchants.value.single().categoryUid)
    }

    @Test
    fun `marking the same occurrence twice records it once`() = runTest {
        markPaid("netflix", oct3)
        repository.upsertRecurring(netflix().copy(nextDueOn = oct3))
        val again = markPaid("netflix", oct3)
        assertEquals(OccurrenceResult.AlreadyPaid(nov3), again)
        assertEquals(1, repository.transactions.value.size)
        assertEquals(nov3, netflix().nextDueOn)
    }

    @Test
    fun `the fields on Mark as paid override the recurring payment's`() = runTest {
        val result = markPaid(
            "netflix",
            oct3,
            PaymentDraft(amountMinor = 699_00, accountUid = "checking", keepCategory = false, categoryUid = null, paidOn = LocalDate.of(2026, 9, 27)),
        ) as OccurrenceResult.Paid
        val tx = repository.getTransaction(result.transactionUid)!!
        assertEquals(699_00L, tx.amountMinor)
        assertEquals("checking", tx.accountUid)
        assertNull(tx.categoryUid)
        assertEquals(LocalDate.of(2026, 9, 27), tx.occurredOn)
    }

    @Test
    fun `an occurrence paid on another phone is stepped over`() = runTest {
        repository.transactions.value = listOf(
            Fixtures.tx(TransactionType.EXPENSE, 649_00, TODAY, "kortex", recurringUid = "netflix", dueOn = nov3)
                .copy(uid = FinanceIds.recurringOccurrence("netflix", nov3)),
        )
        val result = markPaid("netflix", oct3) as OccurrenceResult.Paid
        assertEquals(LocalDate.of(2026, 12, 3), result.nextDueOn)
    }

    @Test
    fun `undo takes the payment back and restores the due date`() = runTest {
        val result = markPaid("netflix", oct3) as OccurrenceResult.Paid
        undo(result.previous, result.transactionUid)
        assertTrue(repository.transactions.value.isEmpty())
        assertEquals(oct3, netflix().nextDueOn)
    }

    @Test
    fun `skip moves the due date without recording anything, and undo puts it back`() = runTest {
        val result = skip("netflix", oct3) as OccurrenceResult.Skipped
        assertEquals(nov3, netflix().nextDueOn)
        assertTrue(repository.transactions.value.isEmpty())
        undo(result.previous, null)
        assertEquals(oct3, netflix().nextDueOn)
    }

    @Test
    fun `a payment from a deleted account isn't recorded`() = runTest {
        repository.deleteAccount("kortex")
        val result = markPaid("netflix", oct3)
        assertEquals(OccurrenceResult.Failed(TransactionSaveResult.UnknownAccount), result)
        assertEquals(oct3, netflix().nextDueOn)
        assertTrue(repository.transactions.value.isEmpty())
    }

    @Test
    fun `a payment on the 31st keeps its day through short months and edits`() = runTest {
        val draft = RecurringDraft("Rent", RecurringKind.FIXED, 20_000_00, Frequency.MONTHLY, LocalDate.of(2026, 10, 31), "checking")
        val uid = (save(null, draft) as RecurringSaveResult.Saved).uid
        assertEquals(31, repository.getRecurring(uid)!!.anchorDay)

        val nov30 = (markPaid(uid, LocalDate.of(2026, 10, 31)) as OccurrenceResult.Paid).nextDueOn
        assertEquals(LocalDate.of(2026, 11, 30), nov30)

        // Editing the amount, with the date left as shown, keeps the 31st.
        save(uid, draft.copy(amountMinor = 21_000_00, nextDueOn = nov30))
        assertEquals(31, repository.getRecurring(uid)!!.anchorDay)
        assertEquals(LocalDate.of(2026, 12, 31), (markPaid(uid, nov30) as OccurrenceResult.Paid).nextDueOn)
    }

    @Test
    fun `saving checks the name, amount, account, category and reminder`() = runTest {
        val draft = RecurringDraft("Netflix", RecurringKind.SUBSCRIPTION, 649_00, Frequency.MONTHLY, oct3, "kortex")
        assertEquals(RecurringSaveResult.BlankName, save(null, draft.copy(name = "  ")))
        assertEquals(RecurringSaveResult.InvalidAmount, save(null, draft.copy(amountMinor = 0)))
        assertEquals(RecurringSaveResult.UnknownAccount, save(null, draft.copy(accountUid = "nope")))
        assertEquals(RecurringSaveResult.WrongCategoryKind, save(null, draft.copy(categoryUid = "salary")))
        assertEquals(RecurringSaveResult.InvalidReminder, save(null, draft.copy(remindDaysBefore = 9)))
        assertEquals(RecurringSaveResult.NotFound, save("missing", draft))
        val weekly = save(null, draft.copy(frequency = Frequency.WEEKLY)) as RecurringSaveResult.Saved
        assertEquals(oct3.dayOfWeek.value, repository.getRecurring(weekly.uid)!!.anchorDay)
    }
}
