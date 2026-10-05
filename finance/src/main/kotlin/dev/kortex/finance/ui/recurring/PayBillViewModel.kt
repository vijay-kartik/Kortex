package dev.kortex.finance.ui.recurring

import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.kortex.finance.domain.port.Clock
import dev.kortex.finance.domain.usecase.ObserveFinance
import dev.kortex.finance.domain.usecase.PayBillResult
import dev.kortex.finance.domain.usecase.PayCardBill
import dev.kortex.finance.ui.common.FinanceFormat
import dev.kortex.finance.ui.common.FinanceNotice
import dev.kortex.finance.ui.common.FinanceNotices
import dev.kortex.finance.ui.common.FinanceUndo
import dev.kortex.mvi.MviViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@HiltViewModel
class PayBillViewModel @Inject constructor(
    private val observeFinance: ObserveFinance,
    private val payCardBill: PayCardBill,
    private val notices: FinanceNotices,
    private val clock: Clock,
) : MviViewModel<PayBillState, PayBillIntent, PayBillEffect>(PayBillState()) {

    private var started = false
    private var statementUid = ""

    fun start(statementUid: String) {
        if (started) return
        started = true
        this.statementUid = statementUid
        viewModelScope.launch {
            val state = PayBillState.build(observeFinance().first(), statementUid, clock.today())
            if (state == null) sendEffect(PayBillEffect.Close) else setState { state }
        }
    }

    override fun handleIntent(intent: PayBillIntent) {
        when (intent) {
            is PayBillIntent.Choose -> setState { copy(choice = intent.choice, error = null) }
            is PayBillIntent.OtherAmount -> setState {
                copy(otherAmount = intent.text.filter { it.isDigit() || it == '.' || it == ',' }.take(15), choice = PayChoice.OTHER, error = null)
            }
            is PayBillIntent.From -> setState { copy(fromAccountUid = intent.uid, error = null) }
            is PayBillIntent.PaidOn -> setState { copy(paidOn = minOf(intent.date, today)) }
            PayBillIntent.Pay -> pay()
        }
    }

    private fun pay() {
        val state = currentState
        if (state.saving || state.loading) return
        val amount = state.payingMinor ?: return setState { copy(error = "Enter an amount above zero.") }
        val from = state.fromAccountUid ?: return setState { copy(error = "Pick the account you paid from.") }
        setState { copy(saving = true) }
        viewModelScope.launch {
            when (val result = payCardBill(statementUid, amount, from, state.paidOn)) {
                is PayBillResult.Paid -> {
                    val left = state.unpaidMinor - amount
                    val tail = if (left > 0) " · ${FinanceFormat.rupees(left, paise = false)} left on the bill" else ""
                    notices.post(
                        FinanceNotice(
                            "Paid ${FinanceFormat.rupees(amount, paise = false)} to ${state.cardLabel}$tail",
                            FinanceUndo.DeleteEntry(result.transactionUid),
                        ),
                    )
                    sendEffect(PayBillEffect.Close)
                }
                else -> setState { copy(saving = false, error = PayBillState.message(result)) }
            }
        }
    }
}
