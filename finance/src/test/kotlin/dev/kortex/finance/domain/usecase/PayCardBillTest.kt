package dev.kortex.finance.domain.usecase

import dev.kortex.finance.FakeFinanceRepository
import dev.kortex.finance.Fixtures
import dev.kortex.finance.Fixtures.kortexStatement
import dev.kortex.finance.domain.calc.Statements
import dev.kortex.finance.domain.model.TransactionType
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class PayCardBillTest {
    private val repository = FakeFinanceRepository()
    private val pay = PayCardBill(repository, AddTransaction(repository, Fixtures.clock), Fixtures.clock)

    @Before
    fun data() {
        repository.accounts.value = listOf(Fixtures.checking, Fixtures.kortex)
        repository.statements.value = listOf(kortexStatement)
    }

    @Test
    fun `paying records a card payment against the statement`() = runTest {
        val paid = pay(kortexStatement.uid, 1_400_00, "checking") as PayBillResult.Paid
        val tx = repository.getTransaction(paid.transactionUid)!!
        assertEquals(TransactionType.CARD_PAYMENT, tx.type)
        assertEquals("kortex", tx.toAccountUid)
        assertEquals(kortexStatement.uid, tx.statementUid)
        assertEquals(0L, Statements.unpaidMinor(kortexStatement, repository.transactions.value))
    }

    @Test
    fun `a bill can't be paid from a card or against a missing statement`() = runTest {
        assertEquals(PayBillResult.Failed(TransactionSaveResult.InvalidTarget), pay(kortexStatement.uid, 200_00, "kortex"))
        assertEquals(PayBillResult.NotFound, pay("missing", 200_00, "checking"))
        assertEquals(PayBillResult.Failed(TransactionSaveResult.InvalidAmount), pay(kortexStatement.uid, 0, "checking"))
    }
}
