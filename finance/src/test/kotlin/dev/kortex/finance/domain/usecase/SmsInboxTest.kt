package dev.kortex.finance.domain.usecase

import dev.kortex.finance.FakeFinanceRepository
import dev.kortex.finance.Fixtures
import dev.kortex.finance.Fixtures.TODAY
import dev.kortex.finance.domain.FinanceIds
import dev.kortex.finance.domain.model.Category
import dev.kortex.finance.domain.model.InboxSms
import dev.kortex.finance.domain.model.InboxStatus
import dev.kortex.finance.domain.model.ReviewReason
import dev.kortex.finance.domain.model.TransactionSource
import dev.kortex.finance.domain.model.TransactionType
import dev.kortex.finance.domain.read.FinanceReader
import dev.kortex.finance.domain.read.Instrument
import dev.kortex.finance.domain.read.MerchantSuggestion
import dev.kortex.finance.domain.read.MoneyDirection
import dev.kortex.finance.domain.read.ReceiptReading
import dev.kortex.finance.domain.read.SmsReading
import dev.kortex.finance.domain.repository.SmsInboxRepository
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SmsInboxTest {
    private class FakeInbox : SmsInboxRepository {
        val rows = MutableStateFlow<Map<String, InboxSms>>(emptyMap())
        override suspend fun add(sms: InboxSms): Boolean {
            if (sms.id in rows.value) return false
            rows.update { it + (sms.id to sms) }
            return true
        }
        override suspend fun get(id: String) = rows.value[id]
        override suspend fun pending(imported: Boolean, limit: Int) = rows.value.values
            .filter { it.status == InboxStatus.PENDING && it.imported == imported }
            .sortedBy { it.receivedAtMillis }
            .take(limit)
        override fun observeToReview(): Flow<List<InboxSms>> =
            rows.map { all -> all.values.filter { it.status == InboxStatus.REVIEW }.sortedByDescending { it.receivedAtMillis } }
        override suspend fun update(sms: InboxSms) = rows.update { it + (sms.id to sms) }
        override suspend fun deleteHandledBefore(millis: Long) =
            rows.update { all -> all.filterValues { !(it.status.handled && it.receivedAtMillis < millis) } }
        override fun observeCount(): Flow<Int> = rows.map { it.size }
        override suspend fun clear() = rows.update { emptyMap() }
    }

    /** An LLM that reads any SMS as a ₹99 debit from ••4471, and records what it was sent. */
    private class FakeReader : FinanceReader {
        val sent = mutableListOf<String>()
        override suspend fun readSms(maskedText: String, today: LocalDate): SmsReading {
            sent += maskedText
            return SmsReading(MoneyDirection.DEBIT, 99_00, last4 = "4471", instrument = Instrument.ACCOUNT, payee = "CHAI POINT")
        }
        override suspend fun readReceipt(maskedText: String, today: LocalDate): ReceiptReading? = null
        override suspend fun suggestMerchant(rawName: String, categories: List<Category>): MerchantSuggestion? = null
    }

    private val repository = FakeFinanceRepository().apply { accounts.value = listOf(Fixtures.checking, Fixtures.kortex) }
    private val inbox = FakeInbox()
    private val now = Fixtures.clock.nowMillis()
    private val receive = ReceiveSms(inbox)

    private fun process(reader: FinanceReader = FinanceReader.None): ProcessSmsInbox {
        val clock = Fixtures.clock
        return ProcessSmsInbox(
            inbox,
            ReadSms(reader, clock),
            PrepareSmsEntry(SuggestMerchant(repository, reader), clock),
            AddTransaction(repository, clock),
            ObserveFinance(repository),
            clock,
        )
    }

    private suspend fun receiveAndProcess(body: String, autoSave: Boolean = true, reader: FinanceReader = FinanceReader.None): Pair<InboxRun, InboxSms> {
        assertTrue(receive("AX-HDFCBK", body, now))
        val run = process(reader)(autoSave)
        return run to inbox.rows.value.getValue(FinanceIds.smsTransaction("", body))
    }

    private val debit = "INR 420.00 debited from a/c **4471 on 29-Sep-26. Info: SWIGGY."

    @Test
    fun `only bank senders are kept, once`() = runTest {
        assertFalse(receive("+919876543210", debit, now))
        assertFalse(receive("57575", debit, now))
        assertFalse(receive("AX-HDFCBK", "   ", now))
        assertTrue(inbox.rows.value.isEmpty())

        assertTrue(receive("AX-HDFCBK", debit, now))
        assertFalse(receive("AX-HDFCBK", "  $debit ", now + 1_000))
        assertEquals(InboxStatus.PENDING, inbox.rows.value.values.single().status)
    }

    @Test
    fun `a clear debit is saved on its own, as the SMS's entry`() = runTest {
        val (run, row) = receiveAndProcess(debit)
        val uid = FinanceIds.smsTransaction("", debit)
        assertEquals(InboxStatus.SAVED, row.status)
        assertEquals(uid, row.transactionUid)
        assertNull(row.body)
        assertEquals(uid, run.saved.single().transactionUid)
        assertEquals(0, run.toReview)
        val entry = repository.transactions.value.single()
        assertEquals(uid, entry.uid)
        assertEquals(TransactionType.EXPENSE, entry.type)
        assertEquals(420_00L, entry.amountMinor)
        assertEquals("checking", entry.accountUid)
        assertEquals(TransactionSource.SMS, entry.source)
        assertEquals(TODAY, entry.occurredOn)
    }

    @Test
    fun `OTPs and adverts are forgotten, and never reach a model`() = runTest {
        val reader = FakeReader()
        val (_, otp) = receiveAndProcess("482913 is your OTP for a txn of Rs.1,200.00 at AMAZON. Do not share it.", reader = reader)
        assertEquals(InboxStatus.NOT_PAYMENT, otp.status)
        assertNull(otp.body)
        val (_, promo) = receiveAndProcess("Congratulations! You are pre-approved for a personal loan. Apply now.", reader = reader)
        assertEquals(InboxStatus.NOT_PAYMENT, promo.status)
        assertTrue(reader.sent.isEmpty())
        assertTrue(repository.transactions.value.isEmpty())
    }

    @Test
    fun `an SMS nothing could read waits in review instead of being dropped`() = runTest {
        val (run, row) = receiveAndProcess("Your a/c XX4471 statement for September is ready to view.")
        assertEquals(InboxStatus.REVIEW, row.status)
        assertEquals(ReviewReason.UNREADABLE, row.reason)
        assertNotNull(row.body)
        assertEquals(1, run.toReview)
    }

    @Test
    fun `what the LLM read is checked first`() = runTest {
        val reader = FakeReader()
        val (_, row) = receiveAndProcess("Your a/c 123456784471 was charged ninety nine rupees for chai", reader = reader)
        assertEquals(ReviewReason.MODEL_READ, row.reason)
        assertEquals("Your a/c XXXXXXXX4471 was charged ninety nine rupees for chai", reader.sent.single())
        assertTrue(repository.transactions.value.isEmpty())
    }

    @Test
    fun `an unknown or shared last 4 is asked about`() = runTest {
        assertEquals(ReviewReason.NO_ACCOUNT, receiveAndProcess("INR 420.00 debited from a/c **9999 on 29-Sep-26. Info: SWIGGY.").second.reason)
        repository.accounts.update { it + Fixtures.savings.copy(last4 = "4471") }
        assertEquals(ReviewReason.NO_ACCOUNT, receiveAndProcess(debit).second.reason)
    }

    @Test
    fun `card payments and refunds are checked first`() = runTest {
        val (_, row) = receiveAndProcess("Payment of Rs.1,400.00 has been received on your ICICI Bank Credit Card XX8824 on 29-09-26. Thank you.")
        assertEquals(ReviewReason.CARD, row.reason)
    }

    @Test
    fun `a likely duplicate is checked first`() = runTest {
        repository.transactions.value = listOf(Fixtures.tx(TransactionType.EXPENSE, 420_00, TODAY, "checking"))
        assertEquals(ReviewReason.DUPLICATE, receiveAndProcess(debit).second.reason)
    }

    @Test
    fun `an SMS dated days before it arrived is checked first`() = runTest {
        assertEquals(ReviewReason.LATE, receiveAndProcess("INR 420.00 debited from a/c **4471 on 20-Sep-26. Info: SWIGGY.").second.reason)
        assertEquals(InboxStatus.SAVED, receiveAndProcess("INR 99.00 debited from a/c **4471 on 27-Sep-26. Info: CHAI POINT.").second.status)
    }

    @Test
    fun `with saving on its own off, clear SMS wait in review`() = runTest {
        val (run, row) = receiveAndProcess(debit, autoSave = false)
        assertEquals(ReviewReason.AUTO_OFF, row.reason)
        assertTrue(run.saved.isEmpty())
        assertTrue(repository.transactions.value.isEmpty())
    }

    @Test
    fun `received and pasted, in either order, is one entry`() = runTest {
        // Pasted first: reading it here finds the entry and says nothing.
        val uid = FinanceIds.smsTransaction("", debit)
        repository.transactions.value = listOf(Fixtures.tx(TransactionType.EXPENSE, 420_00, TODAY, "checking").copy(uid = uid))
        val (run, row) = receiveAndProcess(debit)
        assertEquals(InboxStatus.SAVED, row.status)
        assertEquals(uid, row.transactionUid)
        assertTrue(run.saved.isEmpty())
        assertEquals(1, repository.transactions.value.size)

        // Received first: pasting it afterwards lands on the same id.
        val add = AddTransaction(repository, Fixtures.clock)
        val again = add(TransactionDraft(TransactionType.EXPENSE, 420_00, "checking", source = TransactionSource.SMS, uid = uid))
        assertEquals(TransactionSaveResult.AlreadySaved(uid), again)
    }

    @Test
    fun `handled SMS are forgotten after 30 days, waiting ones aren't`() = runTest {
        val old = now - ProcessSmsInbox.KEEP_MILLIS - 1
        inbox.add(InboxSms("saved", "AX-HDFCBK", null, old, InboxStatus.SAVED, transactionUid = "t"))
        inbox.add(InboxSms("review", "AX-HDFCBK", debit, old, InboxStatus.REVIEW, ReviewReason.NO_ACCOUNT))
        inbox.add(InboxSms("recent", "AX-HDFCBK", null, now, InboxStatus.NOT_PAYMENT))
        process()(autoSave = true)
        assertEquals(setOf("review", "recent"), inbox.rows.value.keys)
    }

    @Test
    fun `a remembered category is named for the notification`() = runTest {
        repository.merchants.value = listOf(dev.kortex.finance.domain.model.Merchant("m", "swiggy", "Swiggy", "food", 0))
        assertEquals("Food", receiveAndProcess(debit).first.saved.single().categoryName)
    }

    private val resolve = ResolveInboxSms(inbox, DeleteTransaction(repository))

    @Test
    fun `undo on the notification deletes the entry and closes the SMS`() = runTest {
        val (run, _) = receiveAndProcess(debit)
        resolve.undoAutoSaved(run.saved.single().smsId)
        assertTrue(repository.transactions.value.isEmpty())
        val row = inbox.rows.value.values.single()
        assertEquals(InboxStatus.DISMISSED, row.status)
        assertNull(row.body)
    }

    @Test
    fun `a dismissed SMS can be put back, and saving from review closes it`() = runTest {
        val (_, waiting) = receiveAndProcess(debit, autoSave = false)
        val before = resolve.dismiss(waiting.id)
        assertEquals(waiting, before)
        assertEquals(InboxStatus.DISMISSED, inbox.rows.value.getValue(waiting.id).status)
        assertNull(resolve.dismiss(waiting.id))

        resolve.restore(before!!)
        assertEquals(waiting, inbox.rows.value.getValue(waiting.id))

        resolve.saved(waiting.id, "tx-1")
        val saved = inbox.rows.value.getValue(waiting.id)
        assertEquals(InboxStatus.SAVED, saved.status)
        assertEquals("tx-1", saved.transactionUid)
        assertNull(saved.body)
    }

    private val importSms = ImportSms(inbox)
    private val day = 24 * 60 * 60 * 1000L

    private fun stored(body: String, daysAgo: Long, sender: String = "AX-HDFCBK") = StoredSms(sender, body, now - daysAgo * day)

    @Test
    fun `earlier SMS are kept apart, through the same gate, once`() = runTest {
        val kept = importSms(
            listOf(
                stored("INR 420.00 debited from a/c **4471 on 20-Sep-26. Info: SWIGGY.", 9),
                stored("INR 420.00 debited from a/c **4471 on 20-Sep-26. Info: SWIGGY.", 9),
                stored("Lunch at 1?", 9, sender = "+919876543210"),
            ),
        )
        assertEquals(1, kept)
        val row = inbox.rows.value.values.single()
        assertTrue(row.imported)
        assertEquals(now - 9 * day, row.receivedAtMillis)
        // A live run leaves them to the import's runs.
        process()(autoSave = true)
        assertEquals(InboxStatus.PENDING, inbox.rows.value.values.single().status)
    }

    @Test
    fun `an earlier SMS dated the day it arrived isn't late, and saves like a live one`() = runTest {
        importSms(listOf(stored("INR 420.00 debited from a/c **4471 on 20-Sep-26. Info: SWIGGY.", 9)))
        val run = process()(autoSave = true, imported = true)
        assertEquals(1, run.saved.size)
        assertEquals(LocalDate.of(2026, 9, 20), repository.transactions.value.single().occurredOn)
    }

    @Test
    fun `an import is read in runs of the limit`() = runTest {
        importSms((1..5).map { stored("INR ${it}00.00 debited from a/c **4471 on 2$it-Sep-26. Info: SHOP $it.", 9L - it) })
        val first = process()(autoSave = true, imported = true, limit = 2)
        assertEquals(2, first.read)
        assertTrue(first.more)
        process()(autoSave = true, imported = true, limit = 2)
        val last = process()(autoSave = true, imported = true, limit = 2)
        assertEquals(1, last.read)
        assertFalse(last.more)
        assertEquals(5, repository.transactions.value.size)
    }

    @Test
    fun `an SMS from before the account was added is checked first`() = runTest {
        repository.accounts.value = listOf(Fixtures.checking.copy(createdAtMillis = now - 2 * day), Fixtures.kortex)
        importSms(listOf(stored("INR 420.00 debited from a/c **4471 on 20-Sep-26. Info: SWIGGY.", 9)))
        process()(autoSave = true, imported = true)
        assertEquals(ReviewReason.BEFORE_ACCOUNT, inbox.rows.value.values.single().reason)
        assertTrue(repository.transactions.value.isEmpty())
    }

    @Test
    fun `an earlier SMS with an entry typed in by hand that day is checked first`() = runTest {
        // Typed in at noon; the SMS says 6:42 PM. Live, that's not a duplicate; imported, it might be.
        val typed = Fixtures.tx(TransactionType.EXPENSE, 420_00, LocalDate.of(2026, 9, 20), "checking")
            .copy(occurredAtMillis = LocalDate.of(2026, 9, 20).atTime(12, 0).atZone(Fixtures.IST).toInstant().toEpochMilli())
        repository.transactions.value = listOf(typed)
        importSms(listOf(stored("INR 420.00 debited from a/c **4471 on 20-Sep-26 at 06:42 PM. Info: SWIGGY.", 9)))
        process()(autoSave = true, imported = true)
        assertEquals(ReviewReason.DUPLICATE, inbox.rows.value.values.single().reason)
    }
}
