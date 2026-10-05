package dev.kortex.finance.domain.usecase

import dev.kortex.finance.domain.model.Account
import dev.kortex.finance.domain.model.CardStatement
import dev.kortex.finance.domain.model.Category
import dev.kortex.finance.domain.model.Recurring
import dev.kortex.finance.domain.model.Transaction
import dev.kortex.finance.domain.repository.FinanceRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

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

/** Emits a new [FinanceSnapshot] whenever any finance table changes. */
class ObserveFinance(private val repository: FinanceRepository) {
    operator fun invoke(): Flow<FinanceSnapshot> = combine(
        repository.observeAccounts(),
        repository.observeTransactions(),
        repository.observeCategories(),
        repository.observeRecurring(),
        repository.observeStatements(),
        ::FinanceSnapshot,
    )
}
