package dev.kortex.finance.domain.usecase

import dev.kortex.finance.FakeFinanceRepository
import dev.kortex.finance.Fixtures
import dev.kortex.finance.Fixtures.IST
import dev.kortex.finance.Fixtures.TODAY
import dev.kortex.finance.domain.FinanceIds
import dev.kortex.finance.domain.model.BuiltInCategories
import dev.kortex.finance.domain.model.TransactionType
import dev.kortex.finance.domain.read.FinanceReader
import dev.kortex.finance.domain.read.Instrument
import dev.kortex.finance.domain.read.MoneyDirection
import dev.kortex.finance.domain.read.ParsedSms
import java.time.LocalTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PrepareSmsEntryTest {
    private val repository = FakeFinanceRepository()
    private val prepare = PrepareSmsEntry(SuggestMerchant(repository, FinanceReader.None), Fixtures.clock)
    private val text = "INR 420.00 debited from a/c **4471 on 29-Sep-26. Info: SWIGGY."

    private fun snapshot(vararg transactions: dev.kortex.finance.domain.model.Transaction) = FinanceSnapshot(
        accounts = listOf(Fixtures.checking, Fixtures.kortex),
        transactions = transactions.toList(),
        categories = BuiltInCategories.all,
        recurring = emptyList(),
        statements = emptyList(),
    )

    private fun sms(
        direction: MoneyDirection = MoneyDirection.DEBIT,
        last4: String? = "4471",
        instrument: Instrument = Instrument.ACCOUNT,
        time: LocalTime? = null,
        ref: String? = null,
        cardPaymentReceived: Boolean = false,
        availableLimitMinor: Long? = null,
    ) = ParsedSms(
        direction = direction,
        amountMinor = 420_00,
        last4 = last4,
        instrument = instrument,
        date = TODAY,
        time = time,
        payee = "SWIGGY",
        ref = ref,
        cardPaymentReceived = cardPaymentReceived,
        availableLimitMinor = availableLimitMinor,
    )

    @Test
    fun `a debit from a known account is an expense on it, with the SMS's id`() = runTest {
        val plan = prepare(text, sms(), snapshot())
        assertEquals(SmsEntryType.EXPENSE, plan.type)
        assertEquals(Fixtures.checking, plan.account)
        assertNull(plan.unknownLast4)
        assertEquals("Swiggy", plan.merchant.name)
        assertEquals(FinanceIds.smsTransaction("", text), plan.uid)
        assertNull(plan.duplicate)
    }

    @Test
    fun `credits are income on an account, and a payment or a refund on a card`() = runTest {
        assertEquals(SmsEntryType.INCOME, prepare(text, sms(direction = MoneyDirection.CREDIT), snapshot()).type)
        val onCard = sms(direction = MoneyDirection.CREDIT, last4 = "8824", instrument = Instrument.CARD)
        assertEquals(SmsEntryType.CARD_PAYMENT, prepare(text, onCard.copy(cardPaymentReceived = true), snapshot()).type)
        val refund = prepare(text, onCard, snapshot())
        assertEquals(SmsEntryType.CARD_REFUND, refund.type)
        assertNull(refund.merchant.name)
    }

    @Test
    fun `an unknown last 4 asks, and an Avl Lmt says it's a card`() = runTest {
        val plan = prepare(text, sms(direction = MoneyDirection.CREDIT, last4 = "9999", instrument = Instrument.UNKNOWN, availableLimitMinor = 10_000_00), snapshot())
        assertNull(plan.account)
        assertEquals("9999", plan.unknownLast4)
        assertEquals(SmsEntryType.CARD_REFUND, plan.type)
    }

    @Test
    fun `the same SMS, the same reference, or the same amount minutes apart is a duplicate`() = runTest {
        val same = Fixtures.tx(TransactionType.EXPENSE, 1_00, TODAY, "savings").copy(uid = FinanceIds.smsTransaction("", text))
        assertEquals(same, prepare(text, sms(), snapshot(same)).duplicate)

        val byRef = Fixtures.tx(TransactionType.EXPENSE, 1_00, TODAY, "savings").copy(sourceRef = "UPI123")
        assertEquals(byRef, prepare(text, sms(ref = "upi123"), snapshot(byRef)).duplicate)

        val at = TODAY.atTime(18, 42).atZone(IST).toInstant().toEpochMilli()
        val near = Fixtures.tx(TransactionType.EXPENSE, 420_00, TODAY, "checking", atMillis = at + 5 * 60_000)
        assertEquals(near, prepare(text, sms(time = LocalTime.of(18, 42)), snapshot(near)).duplicate)
        val far = near.copy(occurredAtMillis = at + 30 * 60_000)
        assertNull(prepare(text, sms(time = LocalTime.of(18, 42)), snapshot(far)).duplicate)
    }

    @Test
    fun `a future date is today, and a time is kept`() = runTest {
        val plan = prepare(text, sms(time = LocalTime.of(18, 42)).copy(date = TODAY.plusDays(3)), snapshot())
        assertEquals(TODAY, plan.date)
        assertEquals(TODAY.atTime(18, 42).atZone(IST).toInstant().toEpochMilli(), plan.occurredAtMillis)
        assertEquals(Fixtures.clock.nowMillis(), prepare(text, sms().copy(date = null), snapshot()).occurredAtMillis)
    }
}
