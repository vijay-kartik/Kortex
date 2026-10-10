package dev.kortex.finance.ui.accounts

import dagger.hilt.android.lifecycle.HiltViewModel
import dev.kortex.finance.domain.model.AccountKind
import dev.kortex.finance.domain.port.Clock
import dev.kortex.finance.domain.usecase.ObserveFinance
import dev.kortex.finance.ui.FinanceRoute
import dev.kortex.mvi.MviViewModel
import javax.inject.Inject
import androidx.lifecycle.viewModelScope
import dev.kortex.finance.domain.usecase.RevealNumber
import dev.kortex.finance.ui.common.FinanceNotice
import dev.kortex.finance.ui.common.FinanceNotices
import dev.kortex.finance.ui.common.ScreenLock
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@HiltViewModel
class AccountsViewModel @Inject constructor(
    observeFinance: ObserveFinance,
    clock: Clock,
) : MviViewModel<AccountsState, AccountsIntent, AccountsEffect>(AccountsState()) {

    init {
        observeFinance().map { AccountsUi.build(it, clock.today()) }.flowOn(observeFinance.dispatcher).reduceInto { it }
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
    private val revealNumber: RevealNumber,
    private val notices: FinanceNotices,
) : MviViewModel<CardsState, CardsIntent, CardsEffect>(CardsState()) {

    private val revealed = MutableStateFlow<Map<String, String>>(emptyMap())
    private val front = MutableStateFlow<String?>(null)

    init {
        combine(observeFinance(), revealed, front) { snapshot, shown, frontUid ->
            val state = CardsUi.build(snapshot, clock.today())
            // A deleted or archived front card falls back to the first one.
            state.copy(revealed = shown, frontUid = frontUid?.takeIf { uid -> state.cards.any { it.uid == uid } } ?: state.cards.firstOrNull()?.uid)
        }.flowOn(observeFinance.dispatcher).reduceInto { it }
    }

    override fun handleIntent(intent: CardsIntent) {
        val route = when (intent) {
            CardsIntent.AddCard -> FinanceRoute.AddAccount(AccountKind.CREDIT_CARD)
            is CardsIntent.Edit -> FinanceRoute.EditAccount(intent.uid)
            is CardsIntent.PayBill -> FinanceRoute.PayBill(intent.statementUid)
            is CardsIntent.Front -> return front.update { intent.uid }
            is CardsIntent.Reveal -> return reveal(intent.uid)
            is CardsIntent.Hide -> return revealed.update { it - intent.uid }
        }
        sendEffect(CardsEffect.Navigate(route))
    }

    private fun reveal(uid: String) {
        viewModelScope.launch {
            val number = revealNumber(uid)
            if (number == null) {
                notices.post(FinanceNotice("Couldn’t show the full number. Check you’re signed in and online, then try again."))
                return@launch
            }
            revealed.update { it + (uid to number) }
            delay(ScreenLock.SHOW_MILLIS)
            revealed.update { it - uid }
        }
    }
}
