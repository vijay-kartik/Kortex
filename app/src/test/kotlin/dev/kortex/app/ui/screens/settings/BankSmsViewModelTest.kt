package dev.kortex.app.ui.screens.settings

import android.content.ContextWrapper
import android.content.SharedPreferences
import dev.kortex.finance.domain.model.InboxSms
import dev.kortex.finance.domain.port.Clock
import dev.kortex.finance.domain.port.ReviewNotices
import dev.kortex.finance.domain.port.SmsImportScheduler
import dev.kortex.finance.domain.repository.SmsInboxRepository
import dev.kortex.finance.sms.BankSmsStore
import java.lang.reflect.Proxy
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Settings › FINANCES bank SMS, with the import jobs, the notification and the inbox faked. */
@OptIn(ExperimentalCoroutinesApi::class)
class BankSmsViewModelTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private val imports = FakeImports()
    private val notices = FakeReviewNotices()
    private val inbox = FakeInbox()

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel() = BankSmsViewModel(BankSmsStore(PrefsContext()), inbox, imports, notices, FixedZone(zone))

    @Test
    fun `startImport reads from the start of the picked day in the phone's zone`() {
        viewModel().startImport(LocalDate.of(2026, 3, 1))

        val expected = ZonedDateTime.of(2026, 3, 1, 0, 0, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals(listOf(expected), imports.started)
    }

    @Test
    fun `clearInbox forgets the kept SMS and cancels the review notification`() {
        viewModel().clearInbox()

        assertTrue(inbox.cleared)
        assertEquals(1, notices.cancelled)
    }

    @Test
    fun `importing follows the import jobs`() = runTest(UnconfinedTestDispatcher()) {
        val vm = viewModel()
        vm.importing.launchIn(backgroundScope)
        assertFalse(vm.importing.value)

        imports.importing.value = true

        assertTrue(vm.importing.value)
    }

    private class FakeImports : SmsImportScheduler {
        val started = mutableListOf<Long>()
        val importing = MutableStateFlow(false)
        override fun importSince(fromMillis: Long) {
            started += fromMillis
        }
        override fun observeImporting(): Flow<Boolean> = importing
        override fun processInbox() = Unit
    }

    private class FakeReviewNotices : ReviewNotices {
        var cancelled = 0
        override fun cancelReview() {
            cancelled++
        }
    }

    private class FakeInbox : SmsInboxRepository {
        var cleared = false
        override suspend fun add(sms: InboxSms) = true
        override suspend fun get(id: String): InboxSms? = null
        override suspend fun pending(imported: Boolean, limit: Int) = emptyList<InboxSms>()
        override fun observeToReview(): Flow<List<InboxSms>> = flowOf(emptyList())
        override suspend fun update(sms: InboxSms) = Unit
        override suspend fun deleteHandledBefore(millis: Long) = Unit
        override fun observeCount(): Flow<Int> = flowOf(0)
        override suspend fun clear() {
            cleared = true
        }
    }

    private class FixedZone(private val zone: ZoneId) : Clock {
        override fun nowMillis() = 0L
        override fun zone() = zone
    }

    /** [BankSmsStore] reads its settings on creation; every read here gives the default it's asked with. */
    private class PrefsContext : ContextWrapper(null) {
        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences =
            Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SharedPreferences::class.java)) { _, _, args -> args?.getOrNull(1) }
                as SharedPreferences
    }
}
