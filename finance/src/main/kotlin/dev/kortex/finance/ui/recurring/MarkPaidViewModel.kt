package dev.kortex.finance.ui.recurring

import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.kortex.finance.domain.calc.Pending
import dev.kortex.finance.domain.calc.RecurringSchedule
import dev.kortex.finance.domain.port.Clock
import dev.kortex.finance.domain.usecase.MarkPaid
import dev.kortex.finance.domain.usecase.ObserveFinance
import dev.kortex.finance.domain.usecase.OccurrenceResult
import dev.kortex.finance.domain.usecase.PaymentDraft
import dev.kortex.finance.domain.usecase.SkipOccurrence
import dev.kortex.finance.ui.common.FinanceFormat
import dev.kortex.finance.ui.common.FinanceNotice
import dev.kortex.finance.ui.common.FinanceNotices
import dev.kortex.finance.ui.common.FinanceUndo
import dev.kortex.mvi.MviViewModel
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.launch

@HiltViewModel
class MarkPaidViewModel @Inject constructor(
    private val observeFinance: ObserveFinance,
    private val markPaid: MarkPaid,
    private val skip: SkipOccurrence,
    private val notices: FinanceNotices,
    private val clock: Clock,
) : MviViewModel<MarkPaidState, MarkPaidIntent, MarkPaidEffect>(MarkPaidState()) {

    private var started = false
    private var recurringUid = ""

    fun start(recurringUid: String, dueOn: LocalDate) {
        if (started) return
        started = true
        this.recurringUid = recurringUid
        val today = clock.today()
        viewModelScope.launch {
            observeFinance().collect { snapshot ->
                val recurring = snapshot.recurring.find { it.uid == recurringUid }
                if (recurring == null) {
                    if (!currentState.saving) sendEffect(MarkPaidEffect.Close)
                    return@collect
                }
                val (accounts, categories) = RecurringFormState.options(snapshot)
                setState {
                    val first = loading
                    copy(
                        loading = false,
                        name = recurring.name,
                        description = "${RecurringLabels.kind(recurring.kind)}, ${RecurringLabels.repeats(recurring.frequency, recurring.interval).lowercase()}",
                        dueOn = dueOn,
                        urgency = Pending.urgency(dueOn, today),
                        nextDueOn = if (dueOn.isBefore(recurring.nextDueOn)) recurring.nextDueOn else RecurringSchedule.nextAfter(recurring, dueOn),
                        today = today,
                        amount = if (first) FinanceFormat.amountInput(recurring.amountMinor) else amount,
                        paidOn = if (first) today else paidOn,
                        accountUid = (if (first) recurring.accountUid else accountUid)?.takeIf { uid -> accounts.any { it.uid == uid } },
                        categoryUid = (if (first) recurring.categoryUid else categoryUid)?.takeIf { uid -> categories.any { it.uid == uid } },
                        accounts = accounts,
                        categories = categories,
                    )
                }
            }
        }
    }

    override fun handleIntent(intent: MarkPaidIntent) {
        when (intent) {
            is MarkPaidIntent.Amount -> setState { copy(amount = intent.text.filter { it.isDigit() || it == '.' || it == ',' }.take(15), error = null) }
            is MarkPaidIntent.PaidOn -> setState { copy(paidOn = minOf(intent.date, today)) }
            is MarkPaidIntent.Account -> setState { copy(accountUid = intent.uid, error = null) }
            is MarkPaidIntent.Category -> setState { copy(categoryUid = intent.uid) }
            MarkPaidIntent.MarkPaid -> pay()
            MarkPaidIntent.Skip -> skipIt()
        }
    }

    private fun pay() {
        val state = currentState
        if (state.saving || state.loading) return
        val amount = FinanceFormat.parseAmount(state.amount) ?: return setState { copy(error = "Enter an amount above zero.") }
        val account = state.accountUid ?: return setState { copy(error = "Pick the account it was paid from.") }
        setState { copy(saving = true) }
        viewModelScope.launch {
            val payment = PaymentDraft(amountMinor = amount, accountUid = account, categoryUid = state.categoryUid, keepCategory = false, paidOn = state.paidOn)
            when (val result = markPaid(recurringUid, state.dueOn, payment)) {
                is OccurrenceResult.Paid -> {
                    notices.post(
                        FinanceNotice(
                            "${state.name} paid · next due ${FinanceFormat.day(result.nextDueOn)}",
                            FinanceUndo.RestoreRecurring(result.previous, result.transactionUid),
                        ),
                    )
                    sendEffect(MarkPaidEffect.Close)
                }
                is OccurrenceResult.AlreadyPaid -> {
                    notices.post(FinanceNotice("${state.name} was already paid · next due ${FinanceFormat.day(result.nextDueOn)}"))
                    sendEffect(MarkPaidEffect.Close)
                }
                else -> setState { copy(saving = false, error = MarkPaidState.message(result)) }
            }
        }
    }

    private fun skipIt() {
        val state = currentState
        if (state.saving || state.loading) return
        setState { copy(saving = true) }
        viewModelScope.launch {
            when (val result = skip(recurringUid, state.dueOn)) {
                is OccurrenceResult.Skipped -> {
                    notices.post(
                        FinanceNotice(
                            "${state.name} skipped · next due ${FinanceFormat.day(result.nextDueOn)}",
                            FinanceUndo.RestoreRecurring(result.previous, null),
                        ),
                    )
                    sendEffect(MarkPaidEffect.Close)
                }
                else -> setState { copy(saving = false, error = MarkPaidState.message(result)) }
            }
        }
    }
}
