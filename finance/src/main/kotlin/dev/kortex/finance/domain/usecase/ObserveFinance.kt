package dev.kortex.finance.domain.usecase

import dev.kortex.finance.domain.model.Account
import dev.kortex.finance.domain.model.CardStatement
import dev.kortex.finance.domain.model.Category
import dev.kortex.finance.domain.model.Recurring
import dev.kortex.finance.domain.model.Transaction
import dev.kortex.finance.domain.repository.FinanceRepository
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn

/** Everything the calculators need, as one consistent picture. */
data class FinanceSnapshot(
    val accounts: List<Account>,
    val transactions: List<Transaction>,
    val categories: List<Category>,
    val recurring: List<Recurring>,
    val statements: List<CardStatement>,
) {
    val accountsByUid: Map<String, Account> by lazy { accounts.associateBy { it.uid } }
    val categoriesByUid: Map<String, Category> by lazy { categories.associateBy { it.uid } }
}

/**
 * Emits a new [FinanceSnapshot] whenever any finance table changes, built on [dispatcher] so the
 * whole ledger is never mapped on the main thread. Screens run their calculators over it on the
 * same [dispatcher] (`flowOn(observeFinance.dispatcher)`), so only the finished state reaches Main.
 */
class ObserveFinance(
    private val repository: FinanceRepository,
    val dispatcher: CoroutineContext = EmptyCoroutineContext,
) {
    operator fun invoke(): Flow<FinanceSnapshot> = combine(
        repository.observeAccounts(),
        repository.observeTransactions(),
        repository.observeCategories(),
        repository.observeRecurring(),
        repository.observeStatements(),
        ::FinanceSnapshot,
    ).flowOn(dispatcher)
}
