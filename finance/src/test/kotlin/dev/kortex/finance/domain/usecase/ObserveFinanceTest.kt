package dev.kortex.finance.domain.usecase

import dev.kortex.finance.FakeFinanceRepository
import dev.kortex.finance.domain.model.Transaction
import dev.kortex.finance.domain.repository.FinanceRepository
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertSame
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ObserveFinanceTest {
    /** Notes which dispatcher the transactions were read and mapped on. */
    private class RecordingRepository(fake: FakeFinanceRepository = FakeFinanceRepository()) : FinanceRepository by fake {
        var readOn: CoroutineContext.Element? = null
        override fun observeTransactions(): Flow<List<Transaction>> = flow {
            readOn = currentCoroutineContext()[ContinuationInterceptor]
            emit(emptyList())
        }
    }

    @Test
    fun `the snapshot is built on the injected dispatcher, not the collector's`() = runTest {
        val background = StandardTestDispatcher(testScheduler, name = "background")
        val repository = RecordingRepository()

        ObserveFinance(repository, background)().first()

        assertSame(background, repository.readOn)
    }
}
