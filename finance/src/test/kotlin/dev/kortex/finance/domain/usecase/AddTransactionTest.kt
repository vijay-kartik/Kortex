package dev.kortex.finance.domain.usecase

import dev.kortex.finance.FakeFinanceRepository
import dev.kortex.finance.Fixtures
import dev.kortex.finance.Fixtures.IST
import dev.kortex.finance.Fixtures.TODAY
import dev.kortex.finance.domain.FinanceIds
import dev.kortex.finance.domain.model.TransactionSource
import dev.kortex.finance.domain.model.TransactionType.CARD_PAYMENT
import dev.kortex.finance.domain.model.TransactionType.EXPENSE
import dev.kortex.finance.domain.model.TransactionType.INCOME
import dev.kortex.finance.domain.model.TransactionType.OPENING
import dev.kortex.finance.domain.model.TransactionType.TRANSFER
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class AddTransactionTest {
    private val repository = FakeFinanceRepository()
    private val add = AddTransaction(repository, Fixtures.clock)

    @Before
    fun accounts() {
        repository.accounts.value = listOf(Fixtures.checking, Fixtures.savings, Fixtures.kortex)
    }

    @Test
    fun `an expense is saved and its merchant remembered with the category`() = runTest {
        val result = add(TransactionDraft(EXPENSE, 42_50, "kortex", categoryUid = "food", merchant = " Whole Foods  Market ")) as TransactionSaveResult.Saved
        val tx = repository.getTransaction(result.uid)!!
        assertEquals(TODAY, tx.occurredOn)
        assertEquals("Whole Foods  Market", tx.merchant)
        assertEquals("whole foods market", tx.payeeKey)
        val merchant = repository.merchants.value.single()
        assertEquals(FinanceIds.merchant("whole foods market"), merchant.uid)
        assertEquals("food", merchant.categoryUid)

        // Uncategorised next time keeps what was learned.
        add(TransactionDraft(EXPENSE, 10_00, "kortex", merchant = "WHOLE FOODS MARKET"))
        assertEquals("food", repository.merchants.value.single().categoryUid)
    }

    @Test
    fun `the day is taken in the device's zone`() = runTest {
        // 20:00 UTC on the 28th is 01:30 on the 29th in India.
        val at = LocalDateTime.of(2026, 9, 28, 20, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
        val result = add(TransactionDraft(EXPENSE, 1_00, "checking", occurredAtMillis = at)) as TransactionSaveResult.Saved
        assertEquals(TODAY, repository.getTransaction(result.uid)!!.occurredOn)
        assertEquals(IST, Fixtures.clock.zone())
    }

    @Test
    fun `the same SMS twice is saved once`() = runTest {
        val uid = FinanceIds.smsTransaction("VM-HDFCBK", "Rs.42.50 spent")
        val draft = TransactionDraft(EXPENSE, 42_50, "kortex", source = TransactionSource.SMS, uid = uid)
        assertEquals(TransactionSaveResult.Saved(uid), add(draft))
        assertEquals(TransactionSaveResult.AlreadySaved(uid), add(draft))
        assertEquals(1, repository.transactions.value.size)
    }

    @Test
    fun `transfers and card payments carry no category or merchant`() = runTest {
        val result = add(TransactionDraft(TRANSFER, 500_00, "checking", toAccountUid = "savings", categoryUid = "food", merchant = "x")) as TransactionSaveResult.Saved
        val tx = repository.getTransaction(result.uid)!!
        assertNull(tx.categoryUid)
        assertNull(tx.merchant)
        assertEquals("savings", tx.toAccountUid)
    }

    @Test
    fun `a card payment keeps its statement`() = runTest {
        val result = add(TransactionDraft(CARD_PAYMENT, 1_400_00, "checking", toAccountUid = "kortex", statementUid = "stmt-sep")) as TransactionSaveResult.Saved
        assertEquals("stmt-sep", repository.getTransaction(result.uid)!!.statementUid)
    }

    @Test
    fun `invalid entries are refused`() = runTest {
        assertEquals(TransactionSaveResult.InvalidAmount, add(TransactionDraft(EXPENSE, 0, "checking")))
        assertEquals(TransactionSaveResult.OpeningNotAllowed, add(TransactionDraft(OPENING, 1_00, "checking")))
        assertEquals(TransactionSaveResult.UnknownAccount, add(TransactionDraft(EXPENSE, 1_00, "nope")))
        assertEquals(TransactionSaveResult.InvalidTarget, add(TransactionDraft(CARD_PAYMENT, 1_00, "checking", toAccountUid = "savings")))
        assertEquals(TransactionSaveResult.InvalidTarget, add(TransactionDraft(CARD_PAYMENT, 1_00, "kortex", toAccountUid = "kortex")))
        assertEquals(TransactionSaveResult.InvalidTarget, add(TransactionDraft(TRANSFER, 1_00, "checking")))
        assertEquals(TransactionSaveResult.InvalidTarget, add(TransactionDraft(TRANSFER, 1_00, "checking", toAccountUid = "kortex")))
        assertEquals(TransactionSaveResult.RefundNotSupported, add(TransactionDraft(INCOME, 1_00, "kortex")))
        assertEquals(TransactionSaveResult.WrongCategoryKind, add(TransactionDraft(EXPENSE, 1_00, "checking", categoryUid = "salary")))
        assertEquals(TransactionSaveResult.WrongCategoryKind, add(TransactionDraft(INCOME, 1_00, "checking", categoryUid = "food")))
        assertEquals(TransactionSaveResult.WrongCategoryKind, add(TransactionDraft(EXPENSE, 1_00, "checking", categoryUid = "deleted")))
        assertEquals(0, repository.transactions.value.size)
    }
}
