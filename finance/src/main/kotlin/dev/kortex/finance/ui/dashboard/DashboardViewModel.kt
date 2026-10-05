package dev.kortex.finance.ui.dashboard

import dagger.hilt.android.lifecycle.HiltViewModel
import dev.kortex.finance.domain.model.AccountKind
import dev.kortex.finance.domain.port.Clock
import dev.kortex.finance.domain.usecase.ObserveFinance
import dev.kortex.finance.ui.FinanceRoute
import dev.kortex.mvi.MviViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.map

@HiltViewModel
class DashboardViewModel @Inject constructor(
    observeFinance: ObserveFinance,
    clock: Clock,
) : MviViewModel<DashboardState, DashboardIntent, DashboardEffect>(DashboardState()) {

    init {
        observeFinance().map { DashboardUi.build(it, clock.today()) }.reduceInto { it }
    }

    override fun handleIntent(intent: DashboardIntent) {
        val route = when (intent) {
            DashboardIntent.AddExpense -> FinanceRoute.AddEntry(income = false)
            DashboardIntent.AddIncome -> FinanceRoute.AddEntry(income = true)
            DashboardIntent.AddAccount -> FinanceRoute.AddAccount(AccountKind.BANK)
            DashboardIntent.OpenPending -> FinanceRoute.Pending
        }
        sendEffect(DashboardEffect.Navigate(route))
    }
}
