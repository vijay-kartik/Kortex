package dev.kortex.finance.ui.accounts

import dagger.hilt.android.lifecycle.HiltViewModel
import dev.kortex.finance.domain.model.AccountKind
import dev.kortex.finance.domain.port.Clock
import dev.kortex.finance.domain.usecase.ObserveFinance
import dev.kortex.finance.ui.FinanceRoute
import dev.kortex.mvi.MviViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.map

@HiltViewModel
class AccountsViewModel @Inject constructor(
    observeFinance: ObserveFinance,
    clock: Clock,
) : MviViewModel<AccountsState, AccountsIntent, AccountsEffect>(AccountsState()) {

    init {
        observeFinance().map { AccountsUi.build(it, clock.today()) }.reduceInto { it }
    }

    override fun handleIntent(intent: AccountsIntent) {
        val route = when (intent) {
            AccountsIntent.Add -> FinanceRoute.AddAccount(AccountKind.BANK)
            is AccountsIntent.Open -> FinanceRoute.EditAccount(intent.uid)
        }
        sendEffect(AccountsEffect.Navigate(route))
    }
}

@HiltViewModel
class CardsViewModel @Inject constructor(
    observeFinance: ObserveFinance,
    clock: Clock,
) : MviViewModel<CardsState, CardsIntent, CardsEffect>(CardsState()) {

    init {
        observeFinance().map { CardsUi.build(it, clock.today()) }.reduceInto { it }
    }

    override fun handleIntent(intent: CardsIntent) {
        val route = when (intent) {
            CardsIntent.AddCard -> FinanceRoute.AddAccount(AccountKind.CREDIT_CARD)
            is CardsIntent.Edit -> FinanceRoute.EditAccount(intent.uid)
        }
        sendEffect(CardsEffect.Navigate(route))
    }
}
