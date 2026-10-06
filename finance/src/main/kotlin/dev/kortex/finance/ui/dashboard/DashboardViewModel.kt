package dev.kortex.finance.ui.dashboard

import dagger.hilt.android.lifecycle.HiltViewModel
import dev.kortex.finance.domain.model.AccountKind
import dev.kortex.finance.domain.port.Clock
import dev.kortex.finance.domain.repository.SmsInboxRepository
import dev.kortex.finance.domain.usecase.ObserveFinance
import dev.kortex.finance.ui.FinanceRoute
import dev.kortex.mvi.MviViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.combine

@HiltViewModel
class DashboardViewModel @Inject constructor(
    observeFinance: ObserveFinance,
    inbox: SmsInboxRepository,
    clock: Clock,
) : MviViewModel<DashboardState, DashboardIntent, DashboardEffect>(DashboardState()) {

    init {
        combine(observeFinance(), inbox.observeToReview()) { snapshot, toReview ->
            DashboardUi.build(snapshot, clock.today()).copy(smsToReview = toReview.size)
        }.reduceInto { it }
    }

    override fun handleIntent(intent: DashboardIntent) {
        val route = when (intent) {
            DashboardIntent.AddExpense -> FinanceRoute.AddEntry(income = false)
            DashboardIntent.AddIncome -> FinanceRoute.AddEntry(income = true)
            DashboardIntent.AddAccount -> FinanceRoute.AddAccount(AccountKind.BANK)
            DashboardIntent.OpenPending -> FinanceRoute.Pending
            DashboardIntent.OpenSmsReview -> FinanceRoute.SmsReview
        }
        sendEffect(DashboardEffect.Navigate(route))
    }
}
