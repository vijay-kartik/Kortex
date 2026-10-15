package dev.kortex.finance.domain.usecase

import dev.kortex.finance.FakeFinanceRepository
import dev.kortex.finance.Fixtures
import dev.kortex.finance.Fixtures.TODAY
import dev.kortex.finance.domain.FinanceIds
import dev.kortex.finance.domain.model.TransactionSource
import dev.kortex.finance.domain.model.TransactionType
import dev.kortex.finance.domain.read.FinanceReader
import dev.kortex.finance.domain.read.Instrument
import dev.kortex.finance.domain.read.MoneyDirection
import dev.kortex.finance.domain.read.ParsedSms
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SaveSmsEntryTest {
    private val repository = FakeFinanceRepository().apply { accounts.value = listOf(Fixtures.checking, Fixtures.kortex) }
    private val clock = Fixtures.clock
    private val prepare = PrepareSmsEntry(SuggestMerchant(repository, FinanceReader.None), clock)
    private val save = SaveSmsEntry(AddTransaction(repository, clock), ObserveFinance(repository), clock)

    private val debitText = "INR 420.00 debited from a/c **4471 on 29-Sep-26. Info: SWIGGY."
    private val paymentText = "Payment of Rs 1,400.00 received on your KORTEX card ending 8824. Thank you."

    private val debit = ParsedSms(MoneyDirection.DEBIT, 420_00, last4 = "4471", instrument = Instrument.ACCOUNT, date = TODAY, payee = "SWIGGY", ref = "UPI123")
    private val payment = ParsedSms(MoneyDirection.CREDIT, 1_400_00, last4 = "8824", instrument = Instrument.CARD, date = TODAY, cardPaymentReceived = true)

    private suspend fun plan(text: String, sms: ParsedSms) = prepare(text, sms, ObserveFinance(repository)().first())

    @Test
    fun `an expense is saved as read, under the SMS's id`() = runTest {
        val plan = plan(debitText, debit)
        val result = save(plan, plan.edits)
        val uid = FinanceIds.smsTransaction("", debitText)
        assertEquals(SmsSaveResult.Done(TransactionSaveResult.Saved(uid)), result)
        val entry = repository.transactions.value.single()
        assertEquals(TransactionType.EXPENSE, entry.type)
        assertEquals(420_00L, entry.amountMinor)
        assertEquals("checking", entry.accountUid)
        assertEquals("Swiggy", entry.merchant)
        assertEquals(TransactionSource.SMS, entry.source)
        assertEquals("UPI123", entry.sourceRef)
        assertEquals(TODAY, entry.occurredOn)
    }

    @Test
    fun `a card payment moves money from the paying account to the card, against its latest statement`() = runTest {
        val older = Fixtures.kortexStatement.copy(uid = "stmt-aug", statementOn = TODAY.minusMonths(2))
        repository.statements.value = listOf(older, Fixtures.kortexStatement)
        val plan = plan(paymentText, payment)
        assertEquals(SmsEntryType.CARD_PAYMENT, plan.type)
        val result = save(plan, plan.edits.copy(fromAccountUid = "checking", merchant = "KORTEX", categoryUid = "food"))
        assertEquals(SmsSaveResult.Done(TransactionSaveResult.Saved(plan.uid)), result)
        val entry = repository.transactions.value.single()
        assertEquals(TransactionType.CARD_PAYMENT, entry.type)
        assertEquals("checking", entry.accountUid)
        assertEquals("kortex", entry.toAccountUid)
        assertEquals("stmt-sep", entry.statementUid)
        assertNull(entry.categoryUid)
        assertNull(entry.merchant)
    }

    @Test
    fun `a card payment with no statement yet settles none`() = runTest {
        val plan = plan(paymentText, payment)
        save(plan, plan.edits.copy(fromAccountUid = "checking"))
        assertNull(repository.transactions.value.single().statementUid)
    }

    @Test
    fun `a card payment needs the card and the account it was paid from`() = runTest {
        val plan = plan(paymentText, payment)
        assertEquals(SmsSaveResult.NoAccount, save(plan, plan.edits))
        assertEquals(SmsSaveResult.NoCard, save(plan, plan.edits.copy(accountUid = null, fromAccountUid = "checking")))
        assertEquals(emptyList<Any>(), repository.transactions.value)
    }

    @Test
    fun `an expense needs the account it was paid with`() = runTest {
        val plan = plan(debitText, debit)
        assertEquals(SmsSaveResult.NoAccount, save(plan, plan.edits.copy(accountUid = null)))
    }

    @Test
    fun `the same SMS saved twice is already saved, and Add anyway makes a second entry`() = runTest {
        val plan = plan(debitText, debit)
        save(plan, plan.edits)
        val again = plan(debitText, debit)
        assertEquals(SmsSaveResult.Done(TransactionSaveResult.AlreadySaved(plan.uid)), save(again, again.edits))
        val anyway = save(again, again.edits, asNew = true) as SmsSaveResult.Done
        val second = (anyway.result as TransactionSaveResult.Saved).uid
        assertNotEquals(plan.uid, second)
        assertEquals(2, repository.transactions.value.size)
    }

    @Test
    fun `an expense with no name is saved under its UPI id`() = runTest {
        val upi = debit.copy(payee = "chaipoint@okaxis", payeeIsUpiId = true)
        val plan = plan(debitText, upi)
        assertEquals("", plan.edits.merchant)
        save(plan, plan.edits.copy(merchant = "  "))
        val entry = repository.transactions.value.single()
        assertEquals("chaipoint@okaxis", entry.merchant)
        assertEquals(FinanceIds.payeeKey("chaipoint@okaxis"), entry.payeeKey)
    }
}
