package dev.kortex.finance.domain.read

import dev.kortex.finance.FakeFinanceRepository
import dev.kortex.finance.Fixtures
import dev.kortex.finance.Fixtures.TODAY
import dev.kortex.finance.domain.FinanceIds
import dev.kortex.finance.domain.model.Category
import dev.kortex.finance.domain.model.CategoryKind
import dev.kortex.finance.domain.model.Merchant
import dev.kortex.finance.domain.model.TransactionType
import dev.kortex.finance.domain.usecase.AddTransaction
import dev.kortex.finance.domain.usecase.MerchantGuess
import dev.kortex.finance.domain.usecase.ReadReceipt
import dev.kortex.finance.domain.usecase.ReadSms
import dev.kortex.finance.domain.usecase.SmsResult
import dev.kortex.finance.domain.usecase.SuggestCategory
import dev.kortex.finance.domain.usecase.SuggestMerchant
import dev.kortex.finance.domain.usecase.TransactionDraft
import dev.kortex.finance.domain.usecase.TransactionSaveResult
import java.time.LocalDate
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadEntriesTest {
    /** A model that answers one SMS and one receipt, and records what it was sent. */
    private class FakeReader : FinanceReader {
        val sent = mutableListOf<String>()
        override suspend fun readSms(maskedText: String, today: LocalDate): SmsReading? {
            sent += maskedText
            return SmsReading(MoneyDirection.DEBIT, 99_00, last4 = "4471", instrument = Instrument.ACCOUNT, payee = "CHAI POINT")
        }
        override suspend fun readReceipt(maskedText: String, today: LocalDate): ReceiptReading? {
            sent += maskedText
            return ReceiptReading(merchant = "CHAI POINT", totals = listOf(TotalCandidate(120_00, "Total")))
        }
        override suspend fun suggestMerchant(rawName: String, categories: List<Category>): MerchantSuggestion? {
            sent += rawName
            return MerchantSuggestion("Decathlon", categoryUid = "travel")
        }
    }

    /** A decision model with fixed answers, recording what it was sent. */
    private class FakeDecider(
        private val kind: Decision<SmsKind>? = null,
        private val category: Decision<String>? = null,
    ) : FinanceDecider {
        val sent = mutableListOf<String>()
        override suspend fun smsKind(maskedText: String): Decision<SmsKind>? = kind.also { sent += maskedText }
        override suspend fun pickCategory(merchant: String, categories: List<Category>): Decision<String>? = category.also { sent += merchant }
    }

    @Test
    fun `an SMS the patterns can't read goes to the model, masked`() = runTest {
        val reader = FakeReader()
        val result = ReadSms(reader, Fixtures.clock)("Your a/c 123456784471 was charged ninety nine rupees for chai") as SmsResult.Read
        assertTrue(result.sms.fromModel)
        assertEquals(99_00L, result.sms.amountMinor)
        assertEquals("Your a/c XXXXXXXX4471 was charged ninety nine rupees for chai", reader.sent.single())
    }

    @Test
    fun `an OTP is never sent anywhere`() = runTest {
        val reader = FakeReader()
        val result = ReadSms(reader, Fixtures.clock)("123456 is your OTP. Do not share.")
        assertEquals(SmsResult.NotAPayment(SmsKind.OTP), result)
        assertTrue(reader.sent.isEmpty())
        assertEquals(SmsResult.NotAPayment(SmsKind.UNREADABLE), ReadSms(FinanceReader.None, Fixtures.clock)("hello there"))
    }

    @Test
    fun `an unreadable SMS the decision model is sure about skips the LLM`() = runTest {
        val text = "Your a/c 123456784471 was charged ninety nine rupees for chai"
        val reader = FakeReader()
        val decider = FakeDecider(kind = Decision(SmsKind.PROMO, 0.95))
        assertEquals(SmsResult.NotAPayment(SmsKind.PROMO), ReadSms(reader, Fixtures.clock, decider)(text))
        assertEquals("Your a/c XXXXXXXX4471 was charged ninety nine rupees for chai", decider.sent.single())
        assertTrue(reader.sent.isEmpty())

        // Unsure, or sure it's a payment: the LLM reads it as before.
        assertTrue(ReadSms(reader, Fixtures.clock, FakeDecider(kind = Decision(SmsKind.PROMO, 0.6)))(text) is SmsResult.Read)
        assertTrue(ReadSms(reader, Fixtures.clock, FakeDecider(kind = Decision(SmsKind.TRANSACTION, 0.99)))(text) is SmsResult.Read)
    }

    @Test
    fun `patterns that place an SMS never ask the decision model`() = runTest {
        val decider = FakeDecider(kind = Decision(SmsKind.PROMO, 0.99))
        assertEquals(SmsResult.NotAPayment(SmsKind.OTP), ReadSms(FakeReader(), Fixtures.clock, decider)("123456 is your OTP. Do not share."))
        assertTrue(decider.sent.isEmpty())
    }

    @Test
    fun `a sure decision model picks the category, else the LLM's pick stands`() = runTest {
        val repository = FakeFinanceRepository()
        val sure = SuggestMerchant(repository, FakeReader(), FakeDecider(category = Decision("food", 0.8)))
        assertEquals(MerchantGuess("Decathlon", "food", MerchantGuess.Source.MODEL), sure("DECATHLON SPORTS", CategoryKind.EXPENSE))
        val unsure = SuggestMerchant(repository, FakeReader(), FakeDecider(category = Decision("food", 0.3)))
        assertEquals(MerchantGuess("Decathlon", "travel", MerchantGuess.Source.MODEL), unsure("DECATHLON SPORTS", CategoryKind.EXPENSE))
    }

    @Test
    fun `a merchant typed by hand gets its remembered category, else a sure pick`() = runTest {
        val repository = FakeFinanceRepository()
        repository.merchants.value = listOf(Merchant("m", "whole foods market", "Whole Foods Market", "food", 0))
        val decider = FakeDecider(category = Decision("travel", 0.9))
        val suggest = SuggestCategory(repository, decider)

        assertEquals(MerchantGuess("Whole Foods Market", "food", MerchantGuess.Source.REMEMBERED), suggest("Whole Foods Market", CategoryKind.EXPENSE))
        assertTrue(decider.sent.isEmpty())
        assertEquals(MerchantGuess("Uber", "travel", MerchantGuess.Source.MODEL), suggest("Uber", CategoryKind.EXPENSE))
        assertNull(SuggestCategory(repository, FakeDecider(category = Decision("travel", 0.4)))("Uber", CategoryKind.EXPENSE))
        // A pick from the other kind's categories is dropped.
        assertNull(suggest("Uber", CategoryKind.INCOME))
        assertNull(SuggestCategory(repository)("Uber", CategoryKind.EXPENSE))
    }

    @Test
    fun `a receipt the patterns half read is finished by the model`() = runTest {
        val receipt = ReadReceipt(FakeReader(), Fixtures.clock)("~~~\nSomething 120.00\n")
        assertEquals("Chai Point", receipt.merchant)
        assertEquals(120_00L, receipt.total)
    }

    @Test
    fun `merchants remembered beat the model, and a UPI id asks for a name`() = runTest {
        val repository = FakeFinanceRepository()
        val reader = FakeReader()
        val suggest = SuggestMerchant(repository, reader)
        repository.merchants.value = listOf(Merchant("m", "whole foods market", "Whole Foods Market", "food", 0))

        assertEquals(MerchantGuess("Whole Foods Market", "food", MerchantGuess.Source.REMEMBERED), suggest("WHOLE FOODS MARKET", CategoryKind.EXPENSE))
        assertEquals(MerchantGuess(null, null, null, needsName = true), suggest("paytmqr2810050@paytm", CategoryKind.EXPENSE, isUpiId = true))
        assertEquals(MerchantGuess("Decathlon", "travel", MerchantGuess.Source.MODEL), suggest("DECATHLON SPORTS", CategoryKind.EXPENSE))
        // An income category isn't suggested for an expense.
        assertEquals(MerchantGuess("Decathlon", null, null), suggest("DECATHLON SPORTS", CategoryKind.INCOME))
        assertEquals("Decathlon Sports", SuggestMerchant(repository, FinanceReader.None)("DECATHLON SPORTS", CategoryKind.EXPENSE).name)
    }

    @Test
    fun `a UPI id named once is remembered by the id`() = runTest {
        val repository = FakeFinanceRepository()
        repository.accounts.value = listOf(Fixtures.checking)
        val saved = AddTransaction(repository, Fixtures.clock)(
            TransactionDraft(TransactionType.EXPENSE, 250_00, "checking", merchant = "Ramesh Chai", payeeKey = "paytmqr2810050@paytm"),
        ) as TransactionSaveResult.Saved
        assertEquals("paytmqr2810050@paytm", repository.getTransaction(saved.uid)!!.payeeKey)
        val merchant = repository.findMerchant(FinanceIds.payeeKey("paytmqr2810050@paytm"))!!
        assertEquals("Ramesh Chai", merchant.displayName)
        assertEquals(MerchantGuess("Ramesh Chai", null, null), SuggestMerchant(repository, FinanceReader.None)("paytmqr2810050@paytm", CategoryKind.EXPENSE, isUpiId = true))
    }

    @Test
    fun `accounts match on the last four, cards first for a card`() {
        val accounts = listOf(Fixtures.checking, Fixtures.kortex, Fixtures.kortex.copy(uid = "other", last4 = "4471", kind = dev.kortex.finance.domain.model.AccountKind.CREDIT_CARD))
        assertEquals("kortex", EntryMatching.accountFor("8824", Instrument.CARD, accounts)?.uid)
        assertEquals("checking", EntryMatching.accountFor("4471", Instrument.ACCOUNT, accounts)?.uid)
        assertEquals("other", EntryMatching.accountFor("4471", Instrument.CARD, accounts)?.uid)
        assertNull(EntryMatching.accountFor("5512", Instrument.CARD, accounts))
    }

    @Test
    fun `the same amount on the same account within ten minutes looks already added`() {
        val at = TODAY.atTime(18, 45).atZone(Fixtures.IST).toInstant().toEpochMilli()
        val earlier = Fixtures.tx(TransactionType.EXPENSE, 42_50, TODAY, "kortex", atMillis = at)
        val list = listOf(earlier)
        fun find(atMillis: Long, ref: String? = null, exact: Boolean = true) =
            EntryMatching.duplicateOf(42_50, "kortex", atMillis, exact, ref, list, EntryMatching.SMS_DUPLICATE_MINUTES) { Fixtures.clock.dayOf(it) }
        assertEquals(earlier, find(at - 3 * 60_000))
        assertNull(find(at - 30 * 60_000))
        assertEquals(earlier, find(at - 5 * 3_600_000, exact = false))
        val withRef = listOf(earlier.copy(sourceRef = "427381920011", amountMinor = 1))
        assertEquals(withRef.single(), EntryMatching.duplicateOf(999, null, 0, true, "427381920011", withRef, 10) { Fixtures.clock.dayOf(it) })
    }
}
