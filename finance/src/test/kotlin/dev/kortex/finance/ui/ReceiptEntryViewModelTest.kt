package dev.kortex.finance.ui

import dev.kortex.finance.FakeFinanceRepository
import dev.kortex.finance.Fixtures
import dev.kortex.finance.Fixtures.IST
import dev.kortex.finance.Fixtures.TODAY
import dev.kortex.finance.domain.model.ReceiptPhoto
import dev.kortex.finance.domain.model.TransactionSource
import dev.kortex.finance.domain.model.TransactionType
import dev.kortex.finance.domain.port.ReceiptImageReader
import dev.kortex.finance.domain.port.ReceiptPhotoStore
import dev.kortex.finance.domain.read.FinanceReader
import dev.kortex.finance.domain.usecase.AddTransaction
import dev.kortex.finance.domain.usecase.AttachReceipt
import dev.kortex.finance.domain.usecase.ObserveFinance
import dev.kortex.finance.domain.usecase.ReadReceipt
import dev.kortex.finance.domain.usecase.SuggestMerchant
import dev.kortex.finance.ui.common.FinanceNotices
import dev.kortex.finance.ui.read.ReceiptEntryIntent
import dev.kortex.finance.ui.read.ReceiptEntryViewModel
import dev.kortex.finance.ui.read.ReceiptStage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/** Scan receipt, from the scanned pages to the saved expense, with the scanner and storage faked. */
@OptIn(ExperimentalCoroutinesApi::class)
class ReceiptEntryViewModelTest {
    /** Figma's receipt, paid on KORTEX ••8824 (Scan receipt 03). */
    private val brewhouse = """
        BREWHOUSE CAFÉ
        100 Ft Rd, Indiranagar
        GSTIN 29ABCDE1234F1Z5
        30/09/2026 13:42      Bill #4821
        Cappuccino x2          420.00
        Avocado toast          380.00
        Banana bread           180.00
        Cold brew              200.00
        Subtotal             1,180.00
        CGST 2.5%               29.50
        SGST 2.5%               29.50
        TOTAL                1,239.00
        PAID VISA ****8824   1,239.00
        Thank you · visit again
    """.trimIndent()

    private class FakeImageReader(private val text: String) : ReceiptImageReader {
        override suspend fun text(pageUris: List<String>) = text
    }

    /** Records what it was asked to keep. */
    private class FakePhotoStore : ReceiptPhotoStore {
        val kept = mutableListOf<Pair<String, String>>()
        override suspend fun keep(pageUri: String, transactionUid: String): ReceiptPhoto {
            kept += pageUri to transactionUid
            return ReceiptPhoto("/receipts/$transactionUid.jpg", "image/jpeg")
        }
    }

    private val repository = FakeFinanceRepository().apply { accounts.value = listOf(Fixtures.kortex) }
    private val photos = FakePhotoStore()

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun scanned(text: String) = ReceiptEntryViewModel(
        imageReader = FakeImageReader(text),
        photoStore = photos,
        observeFinance = ObserveFinance(repository),
        readReceipt = ReadReceipt(FinanceReader.None, Fixtures.clock),
        suggestMerchant = SuggestMerchant(repository, FinanceReader.None),
        addTransaction = AddTransaction(repository, Fixtures.clock),
        attachReceipt = AttachReceipt(repository),
        notices = FinanceNotices(),
        clock = Fixtures.clock,
    ).apply {
        start()
        onIntent(ReceiptEntryIntent.Scanned(listOf("content://scan/page1.jpg")))
    }

    @Test
    fun `a photo with nothing like money on it is unreadable`() = runTest {
        val vm = scanned("BREWHOUSE\n~~ ~~~ ~~\n")
        advanceUntilIdle()
        assertEquals(ReceiptStage.Unreadable, vm.state.value.stage)
    }

    @Test
    fun `a smudged total asks which amount it was`() = runTest {
        val vm = scanned(brewhouse.lines().filterNot { it.startsWith("TOTAL") || it.startsWith("PAID") }.joinToString("\n"))
        advanceUntilIdle()
        val state = vm.state.value
        assertEquals(ReceiptStage.PickTotal, state.stage)
        assertEquals(listOf(1_239_00L, 1_180_00L), state.receipt.candidates.map { it.amountMinor })
    }

    @Test
    fun `the same amount on the same card within the hour is a duplicate`() = runTest {
        val earlier = Fixtures.tx(
            TransactionType.EXPENSE, 1_239_00, TODAY, "kortex",
            atMillis = TODAY.atTime(13, 0).atZone(IST).toInstant().toEpochMilli(),
        )
        repository.transactions.value = listOf(earlier)
        val vm = scanned(brewhouse)
        advanceUntilIdle()
        val state = vm.state.value
        assertEquals(ReceiptStage.Duplicate, state.stage)
        assertEquals(earlier.uid, state.duplicate?.uid)
    }

    @Test
    fun `a clear receipt is saved as an expense on its card, without the photo when not kept`() = runTest {
        val vm = scanned(brewhouse)
        advanceUntilIdle()
        assertEquals(ReceiptStage.Review, vm.state.value.stage)
        vm.onIntent(ReceiptEntryIntent.KeepPhoto(false))
        vm.onIntent(ReceiptEntryIntent.Save)
        advanceUntilIdle()
        val saved = repository.transactions.value.single()
        assertEquals(1_239_00L, saved.amountMinor)
        assertEquals("kortex", saved.accountUid)
        assertEquals(TransactionSource.RECEIPT, saved.source)
        assertEquals(TODAY.atTime(13, 42).atZone(IST).toInstant().toEpochMilli(), saved.occurredAtMillis)
        assertNull(saved.receipt?.photo)
        assertEquals(emptyList<Pair<String, String>>(), photos.kept)
    }

    @Test
    fun `a kept photo is stored under the saved expense's uid`() = runTest {
        val vm = scanned(brewhouse)
        advanceUntilIdle()
        vm.onIntent(ReceiptEntryIntent.Save)
        advanceUntilIdle()
        val saved = repository.transactions.value.single()
        assertEquals(listOf("content://scan/page1.jpg" to saved.uid), photos.kept)
        assertEquals(ReceiptPhoto("/receipts/${saved.uid}.jpg", "image/jpeg"), saved.receipt?.photo)
    }
}
